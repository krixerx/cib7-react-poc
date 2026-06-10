/**
 * Typed client for the public payment-confirmation endpoints used by
 * both flows' state-fee step.
 *
 * The endpoints under `/api/public/payments/**` are unauthenticated
 * (see PublicApiSecurityConfig on the backend). The process instance id
 * in the URL is opaque enough for the POC; production would add a
 * payment-side token or session.
 *
 * Same-origin path: vite.config.ts and nginx.conf proxy `/api/**` to
 * the backend.
 */

const BASE = '/api/public/payments';

export interface PaymentStatus {
  processInstanceId: string;
  processDefinitionKey: string;
  /** "Erki Kriks" — the applicant/founder paying. */
  payerName: string;
  /** "VW Golf 1.4 TSI 2018" (vehicle) or "Acme OÜ" (business). */
  item: string;
  amount: number;
  currency: string;
  /** "Transpordiamet" or "Äriregister (Justiitsministeerium)". */
  recipient: string;
  iban: string;
  /** Payment reference number — currently the process instance id. */
  reference: string;
  /** "pending" | "paid" */
  status: string;
}

export interface ErrorBody {
  code: string;
  message: string;
}

export class PaymentError extends Error {
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
    throw new PaymentError(res.status, body);
  }
  return (await res.json()) as T;
}

export function getStatus(processInstanceId: string): Promise<PaymentStatus> {
  return request(`/${encodeURIComponent(processInstanceId)}/status`);
}

export function confirm(processInstanceId: string): Promise<PaymentStatus> {
  return request(`/${encodeURIComponent(processInstanceId)}/confirm`, {
    method: 'POST',
  });
}
