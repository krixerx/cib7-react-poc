// Authorization predicate for send_account_invitation — the one tool that is
// NOT engine-proxied. Every other tool forwards the caller's Bearer to
// /engine-rest, where the engine re-validates it; the invitation tool runs
// against the Keycloak admin API with the cib7-backend service account, so
// nothing downstream checks the caller. Require both the cib7-rest-api
// audience (only this POC's user-facing clients add it via the
// cib7-rest-api-audience scope) and a known realm role — a bare
// client-credentials token from some other realm client has neither.

import type { JWTPayload } from 'jose';

/** Realm roles allowed to invite new users. */
export const INVITER_ROLES = ['applicant', 'civil-servant', 'cib7-admin'];

/** The audience the access token must carry to use the invitation tool. */
export const INVITER_AUDIENCE = 'cib7-rest-api';

/** Normalises the JWT `aud` claim (string | string[] | absent) to a list. */
export function audiencesOf(claims: JWTPayload | undefined): string[] {
  const aud = claims?.aud;
  return Array.isArray(aud) ? aud : aud ? [aud] : [];
}

/** Realm roles carried by the token, or [] when realm_access is absent. */
export function realmRolesOf(claims: JWTPayload | undefined): string[] {
  const realmAccess = claims?.realm_access as { roles?: string[] } | undefined;
  return realmAccess?.roles ?? [];
}

export function hasInviterAccess(claims: JWTPayload | undefined): boolean {
  return (
    audiencesOf(claims).includes(INVITER_AUDIENCE) &&
    realmRolesOf(claims).some((r) => INVITER_ROLES.includes(r))
  );
}
