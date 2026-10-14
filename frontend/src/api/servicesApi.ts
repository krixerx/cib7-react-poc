import type { ProcessDefinition } from './camundaClient';

/**
 * The services an applicant can start, for the landing page. Anonymous: the
 * backend serves the list at `/api/public/services`, since `/engine-rest`
 * takes no call without a token.
 */
export async function listServices(): Promise<ProcessDefinition[]> {
  const res = await fetch('/api/public/services', { cache: 'no-store' });
  if (!res.ok) {
    throw new Error(`services unavailable (${res.status})`);
  }
  return (await res.json()) as ProcessDefinition[];
}
