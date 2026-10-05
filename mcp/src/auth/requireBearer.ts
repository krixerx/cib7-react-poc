// Authentication at the /mcp door: every request needs the user's own token,
// including the handshake.
//
// Claude (claude.ai, Desktop, mobile) and mcp-remote run the OAuth flow only
// when the server refuses the connection itself. A server that accepts a
// tokenless `initialize` is taken to need no sign-in, and a later 401 on a
// tool call only marks the connector as disconnected: no login window opens,
// and the model is left telling the user to reconnect by hand. Refusing every
// tokenless request makes the client open the Keycloak login when the user
// adds or connects the connector, so tools always run signed in.

import type { NextFunction, Request, Response } from 'express';
import type { JWTPayload } from 'jose';

import type { verifyBearer } from './verify.js';

/**
 * Express middleware for /mcp. A missing or invalid token gets HTTP 401 with a
 * WWW-Authenticate challenge pointing at the protected resource metadata, sent
 * before the MCP SDK runs so the client sees a transport-level refusal. An
 * expired token gets the same answer, which makes the client refresh it.
 */
export function requireBearer(
  verify: typeof verifyBearer,
  metadataUrl: string,
): (req: Request, res: Response, next: NextFunction) => Promise<void> {
  return async (req, res, next) => {
    const result = await verify(req.header('authorization'));
    if (!result.ok) {
      const reason = result.reason ?? 'other';
      res
        .status(401)
        .set(
          'WWW-Authenticate',
          `Bearer resource_metadata="${metadataUrl}", error="invalid_token", error_description="${reason}"` +
            (reason === 'missing' ? ', scope="openid"' : ''),
        )
        .json({ error: 'unauthorized', reason, resource_metadata: metadataUrl });
      return;
    }
    res.locals.claims = result.payload as JWTPayload;
    next();
  };
}
