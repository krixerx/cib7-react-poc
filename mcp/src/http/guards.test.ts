import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express, { type RequestHandler } from 'express';
import { afterEach, describe, expect, it } from 'vitest';

import { allowedHostsFromEnv, hostAndOriginGuard, perIpLimiter, perUserLimiter } from './guards';

let server: Server | undefined;

afterEach(() => {
  server?.close();
  server = undefined;
});

/** Serves POST /mcp behind the given middleware and returns its base URL. */
async function serve(...middleware: RequestHandler[]): Promise<string> {
  const app = express();
  app.post('/mcp', ...middleware, (_req, res) => {
    res.json({ ok: true });
  });
  server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server!.once('listening', resolve));
  return `http://127.0.0.1:${(server!.address() as AddressInfo).port}/mcp`;
}

/** Stand-in for requireBearer: trusts an x-test-sub header as the verified subject. */
const fakeAuth: RequestHandler = (req, res, next) => {
  res.locals.claims = { sub: req.header('x-test-sub') };
  next();
};

describe('allowedHostsFromEnv', () => {
  it('defaults to the resource URL hostname plus loopback', () => {
    expect(allowedHostsFromEnv({ MCP_RESOURCE_URL: 'https://companylab.ai/mcp' }).sort()).toEqual([
      '127.0.0.1',
      '[::1]',
      'companylab.ai',
      'localhost',
    ]);
  });

  it('lets MCP_ALLOWED_HOSTS replace the default', () => {
    expect(allowedHostsFromEnv({ MCP_ALLOWED_HOSTS: ' Example.org , mcp ' })).toEqual([
      'example.org',
      'mcp',
    ]);
  });
});

describe('hostAndOriginGuard', () => {
  it('accepts a listed host on any port with no Origin (native MCP client)', async () => {
    const url = await serve(hostAndOriginGuard(['127.0.0.1']));
    expect((await fetch(url, { method: 'POST' })).status).toBe(200);
  });

  it('rejects a Host that is not listed (DNS rebinding)', async () => {
    const url = await serve(hostAndOriginGuard(['localhost']));
    const res = await fetch(url, { method: 'POST' });
    expect(res.status).toBe(403);
  });

  it('rejects a browser Origin from another site even when Host is allowed', async () => {
    const url = await serve(hostAndOriginGuard(['127.0.0.1']));
    const res = await fetch(url, { method: 'POST', headers: { Origin: 'http://evil.example' } });
    expect(res.status).toBe(403);
  });

  it('accepts a browser Origin on an allowed host', async () => {
    const url = await serve(hostAndOriginGuard(['127.0.0.1', 'localhost']));
    const res = await fetch(url, { method: 'POST', headers: { Origin: 'http://localhost:3000' } });
    expect(res.status).toBe(200);
  });
});

describe('rate limiting', () => {
  it('returns 429 once one user exceeds the per-user limit, without affecting others', async () => {
    const url = await serve(fakeAuth, perUserLimiter(2));
    const as = (sub: string) => fetch(url, { method: 'POST', headers: { 'x-test-sub': sub } });
    expect((await as('bart')).status).toBe(200);
    expect((await as('bart')).status).toBe(200);
    const limited = await as('bart');
    expect(limited.status).toBe(429);
    expect(limited.headers.get('ratelimit')).not.toBeNull();
    expect((await as('homer')).status).toBe(200);
  });

  it('returns 429 once one client IP exceeds the pre-auth limit', async () => {
    const url = await serve(perIpLimiter(1));
    expect((await fetch(url, { method: 'POST' })).status).toBe(200);
    expect((await fetch(url, { method: 'POST' })).status).toBe(429);
  });
});
