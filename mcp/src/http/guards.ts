// HTTP-level guards in front of the /mcp endpoint: DNS-rebinding protection
// (Host and Origin) and request rate limits.
//
// DNS rebinding: the SDK's own option for this (allowedHosts /
// enableDnsRebindingProtection on StreamableHTTPServerTransport) is deprecated
// in SDK 1.29 in favour of external middleware, and compares the raw Host
// header including the port. We use the SDK's hostHeaderValidation middleware,
// which compares hostnames only, so the same list works whether the request
// arrives through nginx (Host: localhost:3000), Traefik (the public host) or
// the compose healthcheck (127.0.0.1:8090).

import type { NextFunction, Request, RequestHandler, Response } from 'express';
import { hostHeaderValidation } from '@modelcontextprotocol/sdk/server/middleware/hostHeaderValidation.js';
import { rateLimit, ipKeyGenerator } from 'express-rate-limit';
import type { JWTPayload } from 'jose';

/**
 * Hostnames /mcp answers to. MCP_ALLOWED_HOSTS (comma-separated hostnames, no
 * ports) replaces the default, which is the hostname of MCP_RESOURCE_URL plus
 * the loopback names, i.e. exactly the hosts the published resource URL and
 * the local healthcheck use.
 */
export function allowedHostsFromEnv(env: NodeJS.ProcessEnv = process.env): string[] {
  const explicit = (env.MCP_ALLOWED_HOSTS ?? '')
    .split(',')
    .map((h) => h.trim().toLowerCase())
    .filter(Boolean);
  if (explicit.length > 0) return explicit;
  const hosts = new Set(['localhost', '127.0.0.1', '[::1]']);
  try {
    hosts.add(new URL(env.MCP_RESOURCE_URL ?? 'http://localhost:3000/mcp').hostname.toLowerCase());
  } catch {
    // A malformed resource URL already breaks the OAuth metadata; keep loopback only.
  }
  return [...hosts];
}

function forbidden(res: Response, message: string): void {
  res.status(403).json({ jsonrpc: '2.0', error: { code: -32000, message }, id: null });
}

/**
 * Rejects requests whose Host, or Origin when a browser sends one, is not in
 * `allowed`. Native MCP clients (mcp-remote, Claude Desktop) send no Origin;
 * a page that rebinds its own domain to this server always does, and its
 * Origin is the attacker's hostname.
 */
export function hostAndOriginGuard(allowed: string[]): RequestHandler {
  const hostCheck = hostHeaderValidation(allowed);
  return (req: Request, res: Response, next: NextFunction) => {
    hostCheck(req, res, () => {
      const origin = req.header('origin');
      if (origin === undefined) return next();
      let hostname: string;
      try {
        hostname = new URL(origin).hostname.toLowerCase();
      } catch {
        return forbidden(res, 'Invalid Origin header');
      }
      if (!allowed.includes(hostname)) return forbidden(res, `Invalid Origin: ${hostname}`);
      next();
    });
  };
}

function tooMany(_req: Request, res: Response): void {
  res.status(429).json({
    jsonrpc: '2.0',
    error: { code: -32000, message: 'Too many requests. Wait a minute and try again.' },
    id: null,
  });
}

/**
 * Coarse per-client-IP limit applied BEFORE token verification, so a flood of
 * junk Bearers cannot keep the JWKS verifier busy. Relies on Express's
 * `trust proxy` setting to see the client address behind nginx / Traefik.
 */
export function perIpLimiter(limitPerMinute: number): RequestHandler {
  return rateLimit({
    windowMs: 60_000,
    limit: limitPerMinute,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    keyGenerator: (req) => ipKeyGenerator(req.ip ?? ''),
    handler: tooMany,
  });
}

/**
 * Per-user limit applied AFTER token verification, keyed by the verified `sub`
 * claim that requireBearer leaves in `res.locals.claims`. Keying on the user
 * rather than the IP is what holds behind a proxy, where every request can
 * share one address.
 */
export function perUserLimiter(limitPerMinute: number): RequestHandler {
  return rateLimit({
    windowMs: 60_000,
    limit: limitPerMinute,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    keyGenerator: (req, res) => {
      const sub = (res.locals.claims as JWTPayload | undefined)?.sub;
      return sub ? `sub:${sub}` : `ip:${ipKeyGenerator(req.ip ?? '')}`;
    },
    handler: tooMany,
  });
}
