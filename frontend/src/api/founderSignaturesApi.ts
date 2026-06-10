/**
 * Typed client for the public co-founder signing endpoints.
 *
 * Mirror of {@link ./ownerConfirmationsApi.ts} — same pattern (per-founder
 * UUID token in the URL, no bearer header), OÜ semantics swapped in.
 *
 * The endpoints under `/api/public/founder-signatures/**` are
 * unauthenticated (see PublicApiSecurityConfig on the backend). Each call
 * carries only the per-founder UUID token in the URL. That's deliberate:
 * co-founders receive these links by email and don't have Keycloak
 * accounts.
 *
 * Same-origin path: the Vite dev server (vite.config.ts) and nginx
 * (nginx.conf) proxy `/api/**` to the backend.
 */

const BASE = '/api/public/founder-signatures';

export interface FounderEntry {
  name: string;
  email: string;
  token: string;
  isApplicant: boolean;
  /** "pending" | "approved" | "rejected" */
  status: string;
  signedAt: string | null;
  reason: string | null;
}

export interface FounderStatus {
  processInstanceId: string;
  applicantName: string;
  companyName: string;
  /** The founder whose token was used to reach this page (the viewer). */
  currentFounder: FounderEntry | null;
  founders: FounderEntry[];
  /** "pending" | "confirmed_waiting" | "ready_to_send" | "sent" | "rejected" */
  state: string;
  rejectedBy: string | null;
  rejectionReason: string | null;
}

export interface ErrorBody {
  code: string;
  message: string;
}

export class FounderSignatureError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  constructor(httpStatus: number, body: ErrorBody) {
    super(body.message);
    this.httpStatus = httpStatus;
    this.code = body.code;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    let body: ErrorBody;
    try {
      body = (await res.json()) as ErrorBody;
    } catch {
      body = { code: 'http_' + res.status, message: res.statusText || 'Request failed' };
    }
    throw new FounderSignatureError(res.status, body);
  }
  return (await res.json()) as T;
}

export function getStatus(token: string): Promise<FounderStatus> {
  return request(`/${encodeURIComponent(token)}/status`);
}

export function approve(token: string): Promise<FounderStatus> {
  return request(`/${encodeURIComponent(token)}`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'approve' }),
  });
}

export function reject(token: string, reason: string): Promise<FounderStatus> {
  return request(`/${encodeURIComponent(token)}`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'reject', reason }),
  });
}

export function submitToRegister(token: string): Promise<FounderStatus> {
  return request(`/${encodeURIComponent(token)}/submit-to-register`, {
    method: 'POST',
  });
}
