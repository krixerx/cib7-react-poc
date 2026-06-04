/**
 * Typed client for the public owner-confirmation endpoints.
 *
 * The endpoints under `/api/public/owner-confirmations/**` are
 * unauthenticated (see PublicApiSecurityConfig on the backend). Each call
 * carries only the per-owner UUID token in the URL — no bearer header.
 * That's deliberate: owners receive these links by email and don't have
 * Keycloak accounts.
 *
 * Same-origin path: the Vite dev server (vite.config.ts) and nginx
 * (nginx.conf) proxy `/api/**` to the backend just like `/engine-rest/**`.
 */

const BASE = '/api/public/owner-confirmations';

export interface OwnerEntry {
  name: string;
  email: string;
  token: string;
  isApplicant: boolean;
  /** "pending" | "approved" | "rejected" */
  status: string;
  signedAt: string | null;
  reason: string | null;
}

export interface OwnerStatus {
  processInstanceId: string;
  applicantName: string;
  /** The owner whose token was used to reach this page (the viewer). */
  currentOwner: OwnerEntry | null;
  owners: OwnerEntry[];
  /** "pending" | "confirmed_waiting" | "ready_to_send" | "sent" | "rejected" */
  state: string;
  rejectedBy: string | null;
  rejectionReason: string | null;
}

export interface ErrorBody {
  code: string;
  message: string;
}

export class OwnerConfirmationError extends Error {
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
    throw new OwnerConfirmationError(res.status, body);
  }
  return (await res.json()) as T;
}

export function getStatus(token: string): Promise<OwnerStatus> {
  return request(`/${encodeURIComponent(token)}/status`);
}

export function approve(token: string): Promise<OwnerStatus> {
  return request(`/${encodeURIComponent(token)}`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'approve' }),
  });
}

export function reject(token: string, reason: string): Promise<OwnerStatus> {
  return request(`/${encodeURIComponent(token)}`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'reject', reason }),
  });
}

export function sendToProcess(token: string): Promise<OwnerStatus> {
  return request(`/${encodeURIComponent(token)}/send-to-process`, {
    method: 'POST',
  });
}
