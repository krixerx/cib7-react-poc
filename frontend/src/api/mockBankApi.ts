/**
 * Client for the demo payment provider (`MockPaymentProvider` in the
 * backend). In production the payer is on the real provider's own site at
 * this point and this module does not exist; see
 * `com.poc.backend.payment.mockprovider`.
 */

import { PaymentError, type ErrorBody } from './paymentsApi';

const BASE = '/api/public/mock-provider/sessions';

export interface BankSession {
  sessionId: string;
  merchantName: string;
  reference: string;
  amount: number;
  currency: string;
  /** "OPEN" | "PAID" | "CANCELLED" */
  status: string;
  /** Merchant page to return to, e.g. `/pay/<token>`. */
  returnPath: string;
}

async function request(path: string, init?: RequestInit): Promise<BankSession> {
  const res = await fetch(BASE + path, init);
  if (!res.ok) {
    let body: ErrorBody;
    try {
      body = (await res.json()) as ErrorBody;
    } catch {
      body = { code: 'http_' + res.status, message: res.statusText || 'Request failed' };
    }
    throw new PaymentError(res.status, body);
  }
  return (await res.json()) as BankSession;
}

export function getSession(sessionId: string): Promise<BankSession> {
  return request(`/${encodeURIComponent(sessionId)}`);
}

export function pay(sessionId: string): Promise<BankSession> {
  return request(`/${encodeURIComponent(sessionId)}/pay`, { method: 'POST' });
}

export function cancel(sessionId: string): Promise<BankSession> {
  return request(`/${encodeURIComponent(sessionId)}/cancel`, { method: 'POST' });
}

/**
 * Only ever send the payer back to a pay page of this SPA, whatever the
 * provider returned, so the bank page cannot become an open redirect.
 */
export function safeReturnPath(path: string | undefined): string | null {
  return path && /^\/pay\/[A-Za-z0-9_.-]+$/.test(path) ? path : null;
}
