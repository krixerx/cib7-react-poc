import { keycloak, ensureFreshToken } from '../auth/keycloak';

/**
 * Pay-page path (`/pay/<token>`) for a case the signed-in user started. The
 * SPA never sees the token from the approval email, so the "Open payment
 * page" buttons ask the backend to mint one; it refuses (404) for anyone but
 * the user who started the case.
 */
export async function getPaymentLink(processInstanceId: string): Promise<string> {
  const token = keycloak.authenticated ? await ensureFreshToken() : null;
  const res = await fetch(`/api/cases/${encodeURIComponent(processInstanceId)}/payment-link`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) {
    throw new Error(`payment link unavailable (${res.status})`);
  }
  const body = (await res.json()) as { path: string };
  return body.path;
}
