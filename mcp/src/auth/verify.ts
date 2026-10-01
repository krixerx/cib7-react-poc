// Bearer token verification at the MCP door.
//
// The sidecar forwards the caller's own Bearer to /engine-rest and the
// backend, both of which validate it again. It verifies here as well for two
// reasons: MCP clients (mcp-remote in particular) need a clean 401 to trigger
// their re-auth flow, and send_account_invitation runs against the Keycloak
// admin API with a service account, so nothing downstream would check the
// caller for that tool.
//
// Checked: signature against Keycloak's JWKS (covers realm rebuild and key
// rotation), expiry, issuer, and audience. The audience must contain the same
// value the engine and backend require (KEYCLOAK_REST_AUDIENCE, default
// cib7-rest-api), so a token Keycloak minted for some other client of the
// realm is refused here instead of being proxied. A token without `aud` fails.

import { createRemoteJWKSet, jwtVerify, type JWTPayload } from 'jose';

import { REQUIRED_AUDIENCE } from './audience.js';

const KEYCLOAK_INTERNAL_URL = process.env.KEYCLOAK_INTERNAL_URL ?? 'http://keycloak:8080';
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM ?? 'cib7-poc';
const KEYCLOAK_ISSUER_URL =
  process.env.KEYCLOAK_ISSUER_URL ?? 'http://localhost:8180/realms/cib7-poc';

// JWKS fetched from the docker-internal URL so the sidecar does not have
// to traverse the host network. `jose` caches keys in-process and refreshes
// on key-rotation (kid miss) automatically.
const jwks = createRemoteJWKSet(
  new URL(`${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs`),
);

export async function verifyBearer(authHeader: string | undefined): Promise<{
  ok: boolean;
  /** Verified claims — only present when ok. Lets non-engine-proxied tools
   *  (send_account_invitation) make their own authorization decisions. */
  payload?: JWTPayload;
  reason?:
    | 'missing'
    | 'malformed'
    | 'invalid_signature'
    | 'expired'
    | 'wrong_issuer'
    | 'wrong_audience'
    | 'other';
}> {
  if (!authHeader) return { ok: false, reason: 'missing' };
  const match = authHeader.match(/^Bearer\s+(\S+)$/i);
  if (!match) return { ok: false, reason: 'malformed' };
  const token = match[1];
  try {
    const { payload } = await jwtVerify(token, jwks, {
      issuer: KEYCLOAK_ISSUER_URL,
      audience: REQUIRED_AUDIENCE,
    });
    return { ok: true, payload };
  } catch (err) {
    const { code, claim } = (err ?? {}) as { code?: string; claim?: string };
    if (code === 'ERR_JWT_EXPIRED') return { ok: false, reason: 'expired' };
    if (code === 'ERR_JWT_CLAIM_VALIDATION_FAILED') {
      return { ok: false, reason: claim === 'aud' ? 'wrong_audience' : 'wrong_issuer' };
    }
    if (code === 'ERR_JWS_SIGNATURE_VERIFICATION_FAILED')
      return { ok: false, reason: 'invalid_signature' };
    return { ok: false, reason: 'other' };
  }
}
