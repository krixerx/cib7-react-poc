import { describe, expect, it } from 'vitest';
import type { JWTPayload } from 'jose';

import { audiencesOf, hasInviterAccess, INVITER_ROLES, realmRolesOf } from './inviterRole';

function claims(aud: string | string[] | undefined, roles?: string[]): JWTPayload {
  return {
    sub: 'user-uuid',
    ...(aud !== undefined ? { aud } : {}),
    ...(roles !== undefined ? { realm_access: { roles } } : {}),
  };
}

describe('hasInviterAccess', () => {
  it.each(INVITER_ROLES)('allows the %s realm role with the cib7-rest-api audience', (role) => {
    expect(hasInviterAccess(claims('cib7-rest-api', [role]))).toBe(true);
  });

  it('accepts aud as an array (Keycloak emits an array for multiple audiences)', () => {
    expect(hasInviterAccess(claims(['account', 'cib7-rest-api'], ['applicant']))).toBe(true);
  });

  it('ignores unrelated roles as long as one inviter role is present', () => {
    expect(
      hasInviterAccess(claims('cib7-rest-api', ['offline_access', 'civil-servant', 'uma'])),
    ).toBe(true);
  });

  it('denies when the audience is right but no inviter role is carried', () => {
    expect(hasInviterAccess(claims('cib7-rest-api', ['offline_access']))).toBe(false);
    expect(hasInviterAccess(claims('cib7-rest-api', []))).toBe(false);
  });

  it('denies when an inviter role is carried but the audience is wrong or missing', () => {
    expect(hasInviterAccess(claims('account', ['applicant']))).toBe(false);
    expect(hasInviterAccess(claims(undefined, ['cib7-admin']))).toBe(false);
  });

  it('denies a bare client-credentials token (no aud, no realm_access)', () => {
    expect(hasInviterAccess({ sub: 'service-account' })).toBe(false);
  });

  it('denies when there are no claims at all (unauthenticated context)', () => {
    expect(hasInviterAccess(undefined)).toBe(false);
  });

  it('does not match inviter roles by substring or prefix', () => {
    expect(hasInviterAccess(claims('cib7-rest-api', ['applicant-readonly']))).toBe(false);
    expect(hasInviterAccess(claims('cib7-rest-api', ['cib7']))).toBe(false);
  });

  it('does not match the audience by substring', () => {
    expect(hasInviterAccess(claims('cib7-rest-api-extended', ['applicant']))).toBe(false);
  });
});

describe('claim normalisation helpers', () => {
  it('audiencesOf handles string, array, and absent aud', () => {
    expect(audiencesOf(claims('cib7-rest-api'))).toEqual(['cib7-rest-api']);
    expect(audiencesOf(claims(['a', 'b']))).toEqual(['a', 'b']);
    expect(audiencesOf(claims(undefined))).toEqual([]);
    expect(audiencesOf(undefined)).toEqual([]);
  });

  it('realmRolesOf handles present, empty, and absent realm_access', () => {
    expect(realmRolesOf(claims('x', ['applicant']))).toEqual(['applicant']);
    expect(realmRolesOf(claims('x', []))).toEqual([]);
    expect(realmRolesOf(claims('x'))).toEqual([]);
    expect(realmRolesOf(undefined)).toEqual([]);
    expect(realmRolesOf({ realm_access: 'not-an-object' } as JWTPayload)).toEqual([]);
  });
});
