// Service-account-backed Keycloak admin REST helpers.
//
// The MCP sidecar's user-facing tools forward the caller's Bearer to
// /engine-rest (stateless Bearer-proxy, decision A2). Inviting a new
// user is different: the caller is asking the system to create someone
// ELSE's account, so we use the cib7-backend service-account client
// (client_credentials grant) for the call instead of the user's token.
//
// Keycloak admin REST exposes `manage-users` to that client via the
// `realm-management` clientRole grant in keycloak/realm-export.json.
//
// Tokens are cached in-process until expiry minus a 5s safety margin.

const KEYCLOAK_INTERNAL_URL =
  process.env.KEYCLOAK_INTERNAL_URL ?? 'http://keycloak:8080'
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM ?? 'cib7-poc'
const CLIENT_ID = process.env.KEYCLOAK_ADMIN_CLIENT_ID ?? 'cib7-backend'
const CLIENT_SECRET =
  process.env.KEYCLOAK_ADMIN_CLIENT_SECRET ?? 'cib7-backend-secret'

const TOKEN_URL = `${KEYCLOAK_INTERNAL_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`
export const ADMIN_BASE = `${KEYCLOAK_INTERNAL_URL}/admin/realms/${KEYCLOAK_REALM}`

interface CachedToken {
  token: string
  expiresAtMs: number
}
let cached: CachedToken | null = null

async function fetchServiceToken(): Promise<CachedToken> {
  const body = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
  })
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(
      `Service-account token fetch failed: ${res.status} ${text.slice(0, 300)}`,
    )
  }
  const data = (await res.json()) as { access_token: string; expires_in: number }
  return {
    token: data.access_token,
    expiresAtMs: Date.now() + data.expires_in * 1000,
  }
}

export async function getServiceToken(): Promise<string> {
  if (cached && cached.expiresAtMs > Date.now() + 5000) {
    return cached.token
  }
  cached = await fetchServiceToken()
  return cached.token
}

export interface AdminEnvelope<T> {
  ok: boolean
  status: number
  code?: string
  message?: string
  data?: T
  /** Keycloak's `Location: .../users/{uuid}` header on POST /users. */
  locationId?: string
}

export async function adminRequest<T = unknown>(
  path: string,
  init: {
    method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
    query?: Record<string, string>
    body?: unknown
  } = {},
): Promise<AdminEnvelope<T>> {
  const token = await getServiceToken()
  const qs = init.query
    ? '?' + new URLSearchParams(init.query).toString()
    : ''
  const res = await fetch(`${ADMIN_BASE}${path}${qs}`, {
    method: init.method ?? 'GET',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  })

  const location = res.headers.get('Location') ?? ''
  const locationId = location.split('/').pop() || undefined
  const text = await res.text()
  let data: unknown = undefined
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }

  if (!res.ok) {
    return {
      ok: false,
      status: res.status,
      code: res.status === 409 ? 'CONFLICT' : 'KEYCLOAK_ERROR',
      message: typeof data === 'string' ? data : JSON.stringify(data),
    }
  }

  return {
    ok: true,
    status: res.status,
    data: data as T,
    locationId,
  }
}
