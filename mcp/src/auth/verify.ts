// Bearer token verification.
//
// The MCP sidecar is a stateless Bearer-proxy (decision A2) — the engine
// remains the authoritative validator on every forwarded call. We added a
// lightweight verification step here ONLY to give MCP clients (mcp-remote
// in particular) a clean 401 signal when the token is stale, so they trigger
// their re-auth flow automatically instead of surfacing the engine's 401 as
// a tool-result error the LLM doesn't know how to recover from.
//
// We verify signature against Keycloak's JWKS (covers realm-rebuild and
// rotation), expiry, and issuer. We deliberately do NOT validate the
// audience here — the engine does that, and adding it would double the
// failure modes the user sees at the same boundary.

import { createRemoteJWKSet, jwtVerify } from 'jose'

const KEYCLOAK_INTERNAL_URL =
  process.env.KEYCLOAK_INTERNAL_URL ?? 'http://keycloak:8080'
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM ?? 'cib7-poc'
const KEYCLOAK_ISSUER_URL =
  process.env.KEYCLOAK_ISSUER_URL ?? 'http://localhost:8180/realms/cib7-poc'

// JWKS fetched from the docker-internal URL so the sidecar does not have
// to traverse the host network. `jose` caches keys in-process and refreshes
// on key-rotation (kid miss) automatically.
const jwks = createRemoteJWKSet(
  new URL(`${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs`),
)

export async function verifyBearer(authHeader: string | undefined): Promise<{
  ok: boolean
  reason?: 'missing' | 'malformed' | 'invalid_signature' | 'expired' | 'wrong_issuer' | 'other'
}> {
  if (!authHeader) return { ok: false, reason: 'missing' }
  const match = authHeader.match(/^Bearer\s+(\S+)$/i)
  if (!match) return { ok: false, reason: 'malformed' }
  const token = match[1]
  try {
    await jwtVerify(token, jwks, { issuer: KEYCLOAK_ISSUER_URL })
    return { ok: true }
  } catch (err) {
    const code = (err as { code?: string })?.code
    if (code === 'ERR_JWT_EXPIRED') return { ok: false, reason: 'expired' }
    if (code === 'ERR_JWT_CLAIM_VALIDATION_FAILED') return { ok: false, reason: 'wrong_issuer' }
    if (code === 'ERR_JWS_SIGNATURE_VERIFICATION_FAILED')
      return { ok: false, reason: 'invalid_signature' }
    return { ok: false, reason: 'other' }
  }
}
