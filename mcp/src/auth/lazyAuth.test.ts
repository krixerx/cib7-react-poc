import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express from 'express';
import { afterEach, describe, expect, it } from 'vitest';

import { lazyBearer, requiresSignIn } from './lazyAuth';
import type { verifyBearer } from './verify';

let server: Server | undefined;

afterEach(() => {
  server?.close();
  server = undefined;
});

const METADATA = 'https://example.org/.well-known/oauth-protected-resource/mcp';

/** Accepts exactly "Bearer good"; anything else is an expired token. */
const fakeVerify: typeof verifyBearer = async (header) =>
  header === 'Bearer good'
    ? { ok: true, payload: { sub: 'u1', preferred_username: 'bart' } }
    : { ok: false, reason: 'expired' };

async function serve(): Promise<string> {
  const app = express();
  app.use(express.json());
  app.all('/mcp', lazyBearer(fakeVerify, METADATA), (_req, res) => {
    res.json({ reached: true, user: res.locals.claims?.preferred_username ?? null });
  });
  server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server!.once('listening', resolve));
  return `http://127.0.0.1:${(server!.address() as AddressInfo).port}/mcp`;
}

function call(name: string, id = 1) {
  return { jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: {} } };
}

async function post(url: string, body: unknown, auth?: string) {
  return fetch(url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(auth ? { authorization: auth } : {}),
    },
    body: JSON.stringify(body),
  });
}

describe('requiresSignIn', () => {
  it('lets the handshake, tools/list and public tools through', () => {
    expect(requiresSignIn({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} })).toBe(false);
    expect(requiresSignIn({ jsonrpc: '2.0', method: 'notifications/initialized' })).toBe(false);
    expect(requiresSignIn({ jsonrpc: '2.0', id: 2, method: 'tools/list' })).toBe(false);
    expect(requiresSignIn(call('list_services'))).toBe(false);
    expect(requiresSignIn(call('get_signup_url'))).toBe(false);
  });

  it('protects user-data tools, unknown tools and unknown methods', () => {
    expect(requiresSignIn(call('list_my_processes'))).toBe(true);
    expect(requiresSignIn(call('send_account_invitation'))).toBe(true);
    expect(requiresSignIn(call('no_such_tool'))).toBe(true);
    expect(requiresSignIn({ jsonrpc: '2.0', id: 3, method: 'resources/list' })).toBe(true);
  });

  it('protects a batch that hides one protected call among public ones', () => {
    expect(requiresSignIn([call('list_services', 1), call('complete_task', 2)])).toBe(true);
  });

  it('treats an empty or malformed body as protected', () => {
    expect(requiresSignIn(undefined)).toBe(true);
    expect(requiresSignIn([])).toBe(true);
    expect(requiresSignIn({ method: 'tools/call', params: { name: 42 } })).toBe(true);
  });
});

describe('lazyBearer', () => {
  it('serves a public tool without a token, with no user attached', async () => {
    const url = await serve();
    const res = await post(url, call('list_services'));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ reached: true, user: null });
  });

  it('answers a protected tool without a token with 401 and a challenge', async () => {
    const url = await serve();
    const res = await post(url, call('list_my_processes'));
    expect(res.status).toBe(401);
    const challenge = res.headers.get('www-authenticate') ?? '';
    expect(challenge).toContain(`resource_metadata="${METADATA}"`);
    expect(challenge).toContain('error="invalid_token"');
    expect(challenge).toContain('scope="openid"');
  });

  it('rejects an invalid token even on a public tool, so the client refreshes', async () => {
    const url = await serve();
    const res = await post(url, call('list_services'), 'Bearer stale');
    expect(res.status).toBe(401);
    expect(res.headers.get('www-authenticate')).toContain('error_description="expired"');
  });

  it('passes a valid token through with its claims', async () => {
    const url = await serve();
    const res = await post(url, call('list_my_processes'), 'Bearer good');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ reached: true, user: 'bart' });
  });

  it('refuses an anonymous GET stream with 405', async () => {
    const url = await serve();
    const res = await fetch(url, { headers: { accept: 'text/event-stream' } });
    expect(res.status).toBe(405);
  });
});
