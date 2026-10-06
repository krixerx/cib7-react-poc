/**
 * Typed client for the public co-founder signing endpoints.
 *
 * Mirror of {@link ./ownerConfirmationsApi.ts} — same pattern (per-founder
 * capability token in the URL, no bearer header), OÜ semantics swapped in.
 *
 * The backend serves every co-signing purpose from one generic endpoint,
 * `/api/public/consent/<purpose>/**`, configured by the service pack's
 * `consent/<purpose>.yaml`; this client talks to purpose `founder` and maps
 * the generic response (case details under `details`, file names under
 * `documents`) onto the founder page's types. The endpoints are
 * unauthenticated. Each call carries only the founder's capability token
 * in the URL: co-founders receive these links by email and don't have
 * Keycloak accounts. The token is signed by the engine and opaque to the
 * SPA (docs/security.md rule 3).
 *
 * Same-origin path: the Vite dev server (vite.config.ts) and nginx
 * (nginx.conf) proxy `/api/**` to the backend.
 */

const BASE = '/api/public/consent/founder';

/**
 * One founder as any link holder may see it: display name and signing state.
 * The backend never returns another party's email or link token.
 */
export interface FounderEntry {
  /** Server-assigned party id ("applicant", "p1", ...); stable within a round. */
  partyId: string;
  name: string;
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
  /** Share capital in EUR as submitted by the applicant. */
  shareCapital: number | null;
  /** Board members by name; personal codes are never sent to this page. */
  boardMembers: { name: string }[];
  /** File name of the Articles of Association, or null when none is on file. */
  articlesFilename: string | null;
  /** The founder whose token was used to reach this page (the viewer). */
  currentFounder: FounderEntry | null;
  founders: FounderEntry[];
  /** "pending" | "confirmed_waiting" | "ready_to_send" | "sent" | "rejected" */
  state: string;
  rejectedBy: string | null;
  rejectionReason: string | null;
}

/** The generic co-signing response (`ConsentController.ConsentStatus`). */
interface ConsentStatusBody {
  processInstanceId: string;
  applicantName: string;
  current: FounderEntry | null;
  parties: FounderEntry[];
  state: string;
  rejectedBy: string | null;
  rejectionReason: string | null;
  details: {
    companyName?: string | null;
    shareCapital?: number | null;
    boardMembers?: { name: string }[] | null;
  };
  documents: { articles?: string | null };
}

function toFounderStatus(body: ConsentStatusBody): FounderStatus {
  return {
    processInstanceId: body.processInstanceId,
    applicantName: body.applicantName,
    companyName: body.details.companyName ?? '',
    shareCapital: body.details.shareCapital ?? null,
    boardMembers: body.details.boardMembers ?? [],
    articlesFilename: body.documents.articles ?? null,
    currentFounder: body.current,
    founders: body.parties,
    state: body.state,
    rejectedBy: body.rejectedBy,
    rejectionReason: body.rejectionReason,
  };
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
  return request<ConsentStatusBody>(`/${encodeURIComponent(token)}/status`).then(toFounderStatus);
}

export function approve(token: string): Promise<FounderStatus> {
  return request<ConsentStatusBody>(`/${encodeURIComponent(token)}`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'approve' }),
  }).then(toFounderStatus);
}

export function reject(token: string, reason: string): Promise<FounderStatus> {
  return request<ConsentStatusBody>(`/${encodeURIComponent(token)}`, {
    method: 'POST',
    body: JSON.stringify({ decision: 'reject', reason }),
  }).then(toFounderStatus);
}

/** A 60-second presigned GET for the case's Articles of Association. */
export function getArticlesDownloadUrl(token: string): Promise<{ url: string; expiresIn: number }> {
  return request(`/${encodeURIComponent(token)}/documents/articles/download-url`);
}

export function submitToRegister(token: string): Promise<FounderStatus> {
  return request<ConsentStatusBody>(`/${encodeURIComponent(token)}/send`, {
    method: 'POST',
  }).then(toFounderStatus);
}
