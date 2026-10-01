/**
 * Typed client for the payment endpoints of the state-fee step shared by all
 * four services.
 *
 * The public endpoints under `/api/public/payments/**` take the payment
 * capability token from the pay link (engine-signed, opaque to the SPA). The
 * browser can only read the bill and start a checkout; whether the fee is
 * paid is decided by the payment provider's signed server-to-server callback
 * (docs/security.md rule 4), never by a call from here.
 *
 * Same-origin path: vite.config.ts and nginx.conf proxy `/api/**` to
 * the backend.
 */

const BASE = '/api/public/payments';

export interface PaymentStatus {
  processDefinitionKey: string;
  /** English service label, e.g. "Vehicle registration state fee". */
  serviceName: string;
  /** "Transpordiamet", "Äriregister (Justiitsministeerium)" or "Transport Authority". */
  recipient: string;
  amount: number;
  currency: string;
  /** Opaque payment reference, stable per case. */
  reference: string;
  /** "pending" | "paid" */
  status: string;
}

export interface CheckoutResponse {
  sessionId: string;
  /** Where to send the browser: the provider's page (in the demo, `/mock-bank/:sessionId`). */
  redirectUrl: string;
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

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
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

export function getStatus(token: string): Promise<PaymentStatus> {
  return request(`${BASE}/${encodeURIComponent(token)}`);
}

export function checkout(token: string): Promise<CheckoutResponse> {
  return request(`${BASE}/${encodeURIComponent(token)}/checkout`, { method: 'POST' });
}
