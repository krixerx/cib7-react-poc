import { describe, expect, it } from 'vitest';

import { decodeBearerUsername } from './identity';

/** Builds an unsigned JWT-shaped string: base64url(header).base64url(payload).sig */
function fakeJwt(payload: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  return `${header}.${body}.fake-signature`;
}

describe('decodeBearerUsername', () => {
  it('extracts preferred_username from the token payload', () => {
    expect(decodeBearerUsername(fakeJwt({ preferred_username: 'lisa' }))).toBe('lisa');
  });

  it('strips a "Bearer " prefix (case-insensitive) before decoding', () => {
    const jwt = fakeJwt({ preferred_username: 'lisa' });
    expect(decodeBearerUsername(`Bearer ${jwt}`)).toBe('lisa');
    expect(decodeBearerUsername(`bearer ${jwt}`)).toBe('lisa');
  });

  it('falls back to the sub claim when preferred_username is absent', () => {
    expect(decodeBearerUsername(fakeJwt({ sub: 'user-uuid-123' }))).toBe('user-uuid-123');
  });

  it('returns "" when the payload carries neither claim', () => {
    expect(decodeBearerUsername(fakeJwt({ aud: 'cib7-engine' }))).toBe('');
  });

  it('returns "" for garbage input instead of throwing', () => {
    expect(decodeBearerUsername('')).toBe('');
    expect(decodeBearerUsername('not-a-jwt')).toBe('');
    expect(decodeBearerUsername('Bearer not-a-jwt')).toBe('');
    expect(decodeBearerUsername('a.%%%not-base64%%%.c')).toBe('');
    expect(
      decodeBearerUsername(
        `${Buffer.from('x').toString('base64url')}.${Buffer.from('not json').toString('base64url')}.c`,
      ),
    ).toBe('');
  });
});
