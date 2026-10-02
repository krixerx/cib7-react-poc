// Lazy authentication at the /mcp door.
//
// A signed-out user can connect, list the tools and call the few that only
// return reference data (the service catalog, how to sign up). Everything
// else needs the user's own token. The refusal has to be an HTTP 401 with a
// WWW-Authenticate challenge sent BEFORE the MCP SDK runs: only a
// transport-level 401 makes Claude (and mcp-remote) start the OAuth flow and
// retry the call, whereas a tool result saying "please sign in" arrives as a
// 200 and is just shown to the model.
//
// The list is an allowlist on purpose: a new tool or JSON-RPC method is
// protected until someone decides it is harmless without an identity.

import type { NextFunction, Request, Response } from 'express';
import type { JWTPayload } from 'jose';

import type { verifyBearer } from './verify.js';

/** Tools that answer from the manifests or build a URL, and never touch user data. */
export const PUBLIC_TOOLS: ReadonlySet<string> = new Set([
  'get_started',
  'list_services',
  'describe_service',
  'get_signup_url',
  'get_password_reset_url',
]);

/** Protocol plumbing a client sends before it calls any tool. */
const PUBLIC_METHODS: ReadonlySet<string> = new Set([
  'initialize',
  'notifications/initialized',
  'notifications/cancelled',
  'ping',
  'tools/list',
]);

function messageIsPublic(msg: unknown): boolean {
  if (!msg || typeof msg !== 'object') return false;
  const { method, params } = msg as { method?: unknown; params?: { name?: unknown } };
  if (typeof method !== 'string') return false;
  if (PUBLIC_METHODS.has(method)) return true;
  return (
    method === 'tools/call' && typeof params?.name === 'string' && PUBLIC_TOOLS.has(params.name)
  );
}

/**
 * True when a JSON-RPC body (one message or a batch) contains anything that
 * needs a signed-in user. A malformed or empty body counts as protected.
 */
export function requiresSignIn(body: unknown): boolean {
  const messages = Array.isArray(body) ? body : [body];
  if (messages.length === 0) return true;
  return !messages.every(messageIsPublic);
}

/**
 * Express middleware for /mcp. A request that carries a token must carry a
 * valid one, whatever it calls, so an expired session still triggers a
 * refresh. A request without a token passes only when every message in it is
 * public; it then reaches the tools with no claims. A tokenless GET (the
 * optional server-to-client SSE stream) gets 405: this stateless server never
 * pushes anything, and an anonymous open stream would only hold a socket.
 */
export function lazyBearer(
  verify: typeof verifyBearer,
  metadataUrl: string,
): (req: Request, res: Response, next: NextFunction) => Promise<void> {
  const challenge = (reason: string, description: string) =>
    `Bearer resource_metadata="${metadataUrl}", error="invalid_token", error_description="${description}"` +
    (reason === 'missing' ? ', scope="openid"' : '');

  return async (req, res, next) => {
    const auth = req.header('authorization');
    if (auth === undefined) {
      if (req.method !== 'POST') {
        res
          .status(405)
          .set('Allow', 'POST')
          .json({
            jsonrpc: '2.0',
            error: { code: -32000, message: 'Method not allowed.' },
            id: null,
          });
        return;
      }
      if (!requiresSignIn(req.body)) {
        res.locals.claims = undefined;
        next();
        return;
      }
      res
        .status(401)
        .set('WWW-Authenticate', challenge('missing', 'Sign in required for this tool'))
        .json({
          error: 'invalid_token',
          error_description: 'Sign in required for this tool',
          resource_metadata: metadataUrl,
        });
      return;
    }

    const result = await verify(auth);
    if (!result.ok) {
      const reason = result.reason ?? 'other';
      res
        .status(401)
        .set('WWW-Authenticate', challenge(reason, reason))
        .json({ error: 'unauthorized', reason, resource_metadata: metadataUrl });
      return;
    }
    res.locals.claims = result.payload as JWTPayload;
    next();
  };
}
