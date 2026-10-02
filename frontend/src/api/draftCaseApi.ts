import { keycloak, ensureFreshToken } from '../auth/keycloak';

async function authHeaders(): Promise<Record<string, string>> {
  const token = keycloak.authenticated ? await ensureFreshToken() : null;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * Ids of the signed-in applicant's draft cases: started but with no task
 * completed yet. Applicants cannot read task history in the engine, so the
 * backend works this out with the service account.
 */
export async function listDraftCaseIds(): Promise<Set<string>> {
  const res = await fetch('/api/cases/drafts', { headers: await authHeaders() });
  if (!res.ok) {
    throw new Error(`drafts unavailable (${res.status})`);
  }
  const body = (await res.json()) as { processInstanceIds: string[] };
  return new Set(body.processInstanceIds);
}

/**
 * Deletes a draft case of the signed-in applicant. The backend refuses with
 * 409 once the first form has been submitted and with 404 for anyone else's
 * case.
 */
export async function deleteDraftCase(processInstanceId: string): Promise<void> {
  const res = await fetch(`/api/cases/${encodeURIComponent(processInstanceId)}`, {
    method: 'DELETE',
    headers: await authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`delete failed (${res.status})`);
  }
}
