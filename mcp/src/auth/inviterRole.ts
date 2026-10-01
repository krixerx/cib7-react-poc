// Authorization predicate and quota for send_account_invitation, the one tool
// that is NOT engine-proxied. Every other tool forwards the caller's Bearer to
// /engine-rest, where the engine re-validates it; the invitation tool runs
// against the Keycloak admin API with the cib7-backend service account, so
// nothing downstream checks the caller. Require both the cib7-rest-api
// audience (only this POC's user-facing clients add it via the
// cib7-rest-api-audience scope) and a known realm role — a bare
// client-credentials token from some other realm client has neither.
//
// Applicants stay on the list on purpose: invite-by-email is the onboarding
// path SERVER_INSTRUCTIONS steers agents to ("add Lisa to the system"), and an
// invited user only ever lands in the /applicant group, so an applicant cannot
// mint anything they could not get by self-registering. What an applicant CAN
// do is make Keycloak send email to arbitrary addresses, so every inviter is
// held to a per-user quota plus a global ceiling (see invitationQuota below).

import type { JWTPayload } from 'jose';

import { FixedWindowCounter } from '../http/fixedWindow.js';
import { REQUIRED_AUDIENCE } from './audience.js';

/** Realm roles allowed to invite new users. */
export const INVITER_ROLES = ['applicant', 'civil-servant', 'cib7-admin'];

/** The audience the access token must carry to use the invitation tool. */
export const INVITER_AUDIENCE = REQUIRED_AUDIENCE;

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

const HOUR_MS = 60 * 60 * 1000;

function positiveInt(raw: string | undefined, fallback: number): number {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : fallback;
}

/**
 * Builds the invitation quota: `perUser` invitations per hour for one Keycloak
 * subject and `global` per hour across everyone, so a batch of fresh
 * self-registered accounts cannot turn the realm into a mail relay either.
 */
export function createInvitationQuota(
  perUser: number,
  global: number,
  now: () => number = Date.now,
): (claims: JWTPayload | undefined) => { ok: true } | { ok: false; retryAfterSeconds: number } {
  const users = new FixedWindowCounter(perUser, HOUR_MS, now);
  const everyone = new FixedWindowCounter(global, HOUR_MS, now);
  return (claims) => {
    const own = users.tryConsume(String(claims?.sub ?? ''));
    if (!own.ok) return own;
    return everyone.tryConsume('*');
  };
}

/** Process-wide quota used by the tool; tune via MCP_INVITES_PER_USER_PER_HOUR / MCP_INVITES_PER_HOUR. */
export const invitationQuota = createInvitationQuota(
  positiveInt(process.env.MCP_INVITES_PER_USER_PER_HOUR, 5),
  positiveInt(process.env.MCP_INVITES_PER_HOUR, 50),
);
