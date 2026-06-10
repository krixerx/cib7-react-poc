// Bearer-proxy fetch wrapper around /engine-rest.
//
// Decision A2 from the eng-review: the MCP service is a stateless proxy. It
// never validates JWTs locally and never refreshes tokens server-side. It
// receives Claude Desktop's Bearer on every tool call, forwards it verbatim
// to the engine, and maps the engine's HTTP response to the standard tool
// envelope so the upstream tool handler can return it without conditionals.
//
// On 401 the engine rejected the token. We surface that as a non-retryable
// envelope; Claude Desktop is responsible for refreshing via its own OAuth
// machinery on the next call. No refresh logic here — that is the load-
// bearing simplicity of Model A.

const ENGINE_URL = process.env.ENGINE_URL ?? 'http://cib7:8080'

// The business microservice that owns /api/** (documents, public
// confirmations, payments). Split out of the engine — see backend/.
const BUSINESS_URL = process.env.BUSINESS_URL ?? 'http://backend:8085'

export interface EngineEnvelope<T = unknown> {
  ok: boolean
  status: number
  code?: string
  message?: string
  retryable?: boolean
  data?: T
}

export interface EngineRequestOptions {
  /** The user's Authorization header, including the "Bearer " prefix. */
  bearer: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** Query params appended to the URL. */
  query?: Record<string, string | undefined>
  /** Body for non-GET methods. Will be JSON-encoded. */
  body?: unknown
}

/**
 * Same Bearer-proxy contract as engineRequest, but against the business
 * microservice (BUSINESS_URL) instead of /engine-rest. Used by tools that
 * hit /api/** endpoints, e.g. upload_document → /api/documents/stage.
 */
export async function businessRequest<T = unknown>(
  path: string,
  opts: EngineRequestOptions,
): Promise<EngineEnvelope<T>> {
  return requestAgainst(BUSINESS_URL, path, opts)
}

export async function engineRequest<T = unknown>(
  path: string,
  opts: EngineRequestOptions,
): Promise<EngineEnvelope<T>> {
  return requestAgainst(ENGINE_URL, path, opts)
}

async function requestAgainst<T = unknown>(
  baseUrl: string,
  path: string,
  opts: EngineRequestOptions,
): Promise<EngineEnvelope<T>> {
  if (!opts.bearer) {
    return {
      ok: false,
      status: 401,
      code: 'no_bearer',
      message: 'MCP tool invoked without an Authorization header. Re-authenticate via Claude Desktop.',
      retryable: false,
    }
  }

  const url = new URL(path, baseUrl)
  if (opts.query) {
    for (const [k, v] of Object.entries(opts.query)) {
      if (v !== undefined) url.searchParams.set(k, v)
    }
  }

  const headers: Record<string, string> = {
    Authorization: opts.bearer,
    Accept: 'application/json',
  }
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let res: Response
  try {
    res = await fetch(url.toString(), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    })
  } catch (e) {
    return {
      ok: false,
      status: 0,
      code: 'unreachable',
      message: `Service unreachable at ${baseUrl}: ${(e as Error).message}`,
      retryable: true,
    }
  }

  // Map engine HTTP responses to the standard envelope. Per the eng-review
  // tool error contract, 401 / 403 / 5xx each get explicit codes; 4xx other
  // than 401/403 surface the body text so the LLM can explain to the user.
  if (res.status === 401) {
    return {
      ok: false,
      status: 401,
      code: 'engine_unauthorized',
      message:
        'Engine rejected the Bearer token. Claude Desktop should re-authenticate against Keycloak.',
      retryable: false,
    }
  }
  if (res.status === 403) {
    return {
      ok: false,
      status: 403,
      code: 'forbidden',
      message:
        'Engine denied access to this resource for the authenticated user. Check group memberships and BPMN candidateGroups.',
      retryable: false,
    }
  }
  if (res.status >= 500) {
    return {
      ok: false,
      status: res.status,
      code: 'engine_error',
      message: `Engine returned ${res.status} ${res.statusText}`,
      retryable: true,
    }
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    return {
      ok: false,
      status: res.status,
      code: 'engine_4xx',
      message: body || `Engine returned ${res.status} ${res.statusText}`,
      retryable: false,
    }
  }

  const data = (await res.json().catch(() => null)) as T | null
  return { ok: true, status: res.status, data: data ?? (undefined as unknown as T) }
}

export function engineBaseUrl(): string {
  return ENGINE_URL
}
