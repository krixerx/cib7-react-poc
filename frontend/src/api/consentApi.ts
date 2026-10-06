/**
 * Client for the backend's co-signing endpoints, `/api/public/consent/<purpose>/<token>`. One
 * client for every purpose a service pack declares (`backend/consent/<purpose>.yaml`): the
 * backend serves them all the same way, and what a purpose shows (details, documents) comes in
 * the status response, by name, as the pack declares it.
 *
 * Unauthenticated: the capability token in the URL is the credential (docs/security.md rule 3).
 */

/** One signing party as another party may see it: display name and state, no email or token. */
export interface ConsentParty {
  /** Server-assigned party id ("applicant", "p1", ...); stable within a round. */
  partyId: string;
  name: string;
  isApplicant: boolean;
  /** "pending" | "approved" | "rejected" */
  status: string;
  signedAt: string | null;
  reason: string | null;
}

/** A detail value as the backend reduces it: text, number, or a list of people by name. */
export type ConsentDetail = string | number | { name: string }[] | null;

export interface ConsentStatus {
  processInstanceId: string;
  applicantName: string;
  /** The party whose token opened the page (the viewer). */
  current: ConsentParty | null;
  parties: ConsentParty[];
  /** "pending" | "confirmed_waiting" | "ready_to_send" | "sent" | "rejected" */
  state: string;
  rejectedBy: string | null;
  rejectionReason: string | null;
  /** The descriptor's details, in its order. */
  details: Record<string, ConsentDetail>;
  /** The descriptor's documents: file name, or null when none is on file. */
  documents: Record<string, string | null>;
}

export interface ErrorBody {
  code: string;
  message: string;
}

export class ConsentError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  constructor(httpStatus: number, body: ErrorBody) {
    super(body.message);
    this.httpStatus = httpStatus;
    this.code = body.code;
  }
}

function base(purpose: string, token: string): string {
  return `/api/public/consent/${encodeURIComponent(purpose)}/${encodeURIComponent(token)}`;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    let body: ErrorBody;
    try {
      body = (await res.json()) as ErrorBody;
    } catch {
      body = { code: 'http_' + res.status, message: res.statusText || 'Request failed' };
    }
    throw new ConsentError(res.status, body);
  }
  return (await res.json()) as T;
}

export function getStatus(purpose: string, token: string): Promise<ConsentStatus> {
  return request<ConsentStatus>(`${base(purpose, token)}/status`);
}

export function approve(purpose: string, token: string): Promise<ConsentStatus> {
  return request<ConsentStatus>(base(purpose, token), {
    method: 'POST',
    body: JSON.stringify({ decision: 'approve' }),
  });
}

export function reject(purpose: string, token: string, reason: string): Promise<ConsentStatus> {
  return request<ConsentStatus>(base(purpose, token), {
    method: 'POST',
    body: JSON.stringify({ decision: 'reject', reason }),
  });
}

/** Forwards the case once every party has signed; any party may do it. */
export function send(purpose: string, token: string): Promise<ConsentStatus> {
  return request<ConsentStatus>(`${base(purpose, token)}/send`, { method: 'POST' });
}

/** A short-lived download URL for one of the descriptor's documents. */
export function getDocumentDownloadUrl(
  purpose: string,
  token: string,
  name: string,
): Promise<{ url: string }> {
  return request<{ url: string }>(
    `${base(purpose, token)}/documents/${encodeURIComponent(name)}/download-url`,
  );
}
