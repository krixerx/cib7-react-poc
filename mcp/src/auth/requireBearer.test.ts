import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import express from 'express';
import { afterEach, describe, expect, it } from 'vitest';

import { requireBearer } from './requireBearer';
import type { verifyBearer } from './verify';

let server: Server | undefined;

afterEach(() => {
  server?.close();
  server = undefined;
});

const METADATA = 'https://example.org/.well-known/oauth-protected-resource/mcp';

/** Accepts exactly "Bearer good"; no header is missing, anything else expired. */
const fakeVerify: typeof verifyBearer = async (header) => {
  if (header === undefined) return { ok: false, reason: 'missing' };
  return header === 'Bearer good'
    ? { ok: true, payload: { sub: 'u1', preferred_username: 'bart' } }
    : { ok: false, reason: 'expired' };
};

async function serve(): Promise<string> {
  const app = express();
  app.use(express.json());
  app.all('/mcp', requireBearer(fakeVerify, METADATA), (_req, res) => {
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

describe('requireBearer', () => {
  it('refuses a tokenless handshake, so the client signs in at connect time', async () => {
    const url = await serve();
    const res = await post(url, { jsonrpc: '2.0', id: 1, method: 'initialize', params: {} });
    expect(res.status).toBe(401);
    const challenge = res.headers.get('www-authenticate') ?? '';
    expect(challenge).toContain(`resource_metadata="${METADATA}"`);
    expect(challenge).toContain('error="invalid_token"');
    expect(challenge).toContain('scope="openid"');
  });

  it('refuses tools/list and formerly public tools without a token', async () => {
    const url = await serve();
    expect((await post(url, { jsonrpc: '2.0', id: 2, method: 'tools/list' })).status).toBe(401);
    expect((await post(url, call('get_started'))).status).toBe(401);
    expect((await post(url, call('list_services'))).status).toBe(401);
  });

  it('refuses a tokenless GET stream', async () => {
    const url = await serve();
    const res = await fetch(url, { headers: { accept: 'text/event-stream' } });
    expect(res.status).toBe(401);
  });

  it('answers an expired token with 401 so the client refreshes it', async () => {
    const url = await serve();
    const res = await post(url, call('list_services'), 'Bearer stale');
    expect(res.status).toBe(401);
    const challenge = res.headers.get('www-authenticate') ?? '';
    expect(challenge).toContain('error_description="expired"');
    expect(challenge).not.toContain('scope=');
  });

  it('passes a valid token through with its claims', async () => {
    const url = await serve();
    const res = await post(url, call('list_my_processes'), 'Bearer good');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ reached: true, user: 'bart' });
  });
});
