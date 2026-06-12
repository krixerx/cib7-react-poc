import { beforeAll, describe, expect, it, vi } from 'vitest';

// verify.ts builds its JWKS resolver at import time via createRemoteJWKSet,
// which would hit the network. Swap ONLY that factory for one that resolves
// our locally generated test key — jwtVerify itself stays real, so the tests
// exercise jose's actual signature / expiry / issuer validation paths.
const keyHolder = vi.hoisted(() => ({ publicKey: undefined as unknown }));

vi.mock('jose', async (importOriginal) => {
  const actual = await importOriginal<typeof import('jose')>();
  return {
    ...actual,
    createRemoteJWKSet: () => async () => {
      if (!keyHolder.publicKey) throw new Error('test key pair not generated yet');
      return keyHolder.publicKey;
    },
  };
});

import { generateKeyPair, SignJWT, type JWTPayload, type KeyLike } from 'jose';

import { verifyBearer } from './verify';

/** Must match verify.ts's KEYCLOAK_ISSUER_URL fallback (env unset in tests). */
const ISSUER = 'http://localhost:8180/realms/cib7-poc';

let privateKey: KeyLike;
let strangerKey: KeyLike;

beforeAll(async () => {
  const realm = await generateKeyPair('RS256');
  privateKey = realm.privateKey;
  keyHolder.publicKey = realm.publicKey;
  // A second key pair Keycloak has never published — simulates a token from
  // a rebuilt realm (the dev-box scenario this verification exists for).
  const stranger = await generateKeyPair('RS256');
  strangerKey = stranger.privateKey;
});

interface SignOptions {
  issuer?: string | null;
  /** Epoch seconds; defaults to 5 minutes from now. */
  exp?: number;
  key?: KeyLike;
}

function sign(claims: JWTPayload = {}, opts: SignOptions = {}): Promise<string> {
  const { issuer = ISSUER, exp = Math.floor(Date.now() / 1000) + 300, key } = opts;
  let jwt = new SignJWT(claims).setProtectedHeader({ alg: 'RS256' }).setExpirationTime(exp);
  if (issuer !== null) jwt = jwt.setIssuer(issuer);
  return jwt.sign(key ?? privateKey);
}

describe('verifyBearer', () => {
  it('accepts a well-signed, unexpired token from the right issuer', async () => {
    const token = await sign({ preferred_username: 'lisa' });
    const result = await verifyBearer(`Bearer ${token}`);
    expect(result.ok).toBe(true);
    expect(result.payload?.preferred_username).toBe('lisa');
    expect(result.payload?.iss).toBe(ISSUER);
  });

  it('accepts a lowercase "bearer" scheme (RFC 9110 schemes are case-insensitive)', async () => {
    const token = await sign();
    const result = await verifyBearer(`bearer ${token}`);
    expect(result.ok).toBe(true);
  });

  it('reports "missing" when there is no Authorization header', async () => {
    expect(await verifyBearer(undefined)).toEqual({ ok: false, reason: 'missing' });
    expect(await verifyBearer('')).toEqual({ ok: false, reason: 'missing' });
  });

  it('reports "malformed" for non-Bearer or mis-shaped headers', async () => {
    const token = await sign();
    expect(await verifyBearer(token)).toEqual({ ok: false, reason: 'malformed' });
    expect(await verifyBearer('Bearer')).toEqual({ ok: false, reason: 'malformed' });
    expect(await verifyBearer('Bearer  ')).toEqual({ ok: false, reason: 'malformed' });
    expect(await verifyBearer(`Bearer ${token} trailing`)).toEqual({
      ok: false,
      reason: 'malformed',
    });
    expect(await verifyBearer(`Basic ${token}`)).toEqual({ ok: false, reason: 'malformed' });
  });

  it('reports "expired" for a correctly signed but stale token', async () => {
    const token = await sign({}, { exp: Math.floor(Date.now() / 1000) - 60 });
    const result = await verifyBearer(`Bearer ${token}`);
    expect(result).toEqual({ ok: false, reason: 'expired' });
  });

  it('reports "wrong_issuer" when iss does not match KEYCLOAK_ISSUER_URL', async () => {
    const token = await sign({}, { issuer: 'http://evil.example/realms/cib7-poc' });
    const result = await verifyBearer(`Bearer ${token}`);
    expect(result).toEqual({ ok: false, reason: 'wrong_issuer' });
  });

  it('reports "wrong_issuer" when the iss claim is absent entirely', async () => {
    const token = await sign({}, { issuer: null });
    const result = await verifyBearer(`Bearer ${token}`);
    expect(result).toEqual({ ok: false, reason: 'wrong_issuer' });
  });

  it('reports "invalid_signature" for a token signed with an unknown key', async () => {
    const token = await sign({}, { key: strangerKey });
    const result = await verifyBearer(`Bearer ${token}`);
    expect(result).toEqual({ ok: false, reason: 'invalid_signature' });
  });

  it('reports "invalid_signature" for a tampered payload', async () => {
    const token = await sign({ preferred_username: 'lisa' });
    const [header, , signature] = token.split('.');
    const forgedPayload = Buffer.from(
      JSON.stringify({
        iss: ISSUER,
        exp: Math.floor(Date.now() / 1000) + 300,
        preferred_username: 'admin',
      }),
    ).toString('base64url');
    const result = await verifyBearer(`Bearer ${header}.${forgedPayload}.${signature}`);
    expect(result).toEqual({ ok: false, reason: 'invalid_signature' });
  });

  it('reports "other" for token-shaped garbage instead of throwing', async () => {
    expect(await verifyBearer('Bearer not-a-jwt')).toEqual({ ok: false, reason: 'other' });
    expect(await verifyBearer('Bearer a.b.c')).toEqual({ ok: false, reason: 'other' });
  });

  it('never exposes a payload on a failed verification', async () => {
    const expired = await sign(
      { preferred_username: 'lisa' },
      { exp: Math.floor(Date.now() / 1000) - 60 },
    );
    const result = await verifyBearer(`Bearer ${expired}`);
    expect(result.ok).toBe(false);
    expect(result.payload).toBeUndefined();
  });
});
