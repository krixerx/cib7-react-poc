import Keycloak from 'keycloak-js';

/**
 * Singleton Keycloak instance shared by the whole SPA.
 *
 * The defaults match the docker-compose setup (Keycloak on host port 8180,
 * realm `cib7-poc`, public client `cib7-frontend`). Override at build time
 * with `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, and `VITE_KEYCLOAK_CLIENT_ID`.
 */
export const keycloak = new Keycloak({
  url:      (import.meta.env.VITE_KEYCLOAK_URL      as string | undefined) ?? 'http://localhost:8180',
  realm:    (import.meta.env.VITE_KEYCLOAK_REALM    as string | undefined) ?? 'cib7-poc',
  clientId: (import.meta.env.VITE_KEYCLOAK_CLIENT_ID as string | undefined) ?? 'cib7-frontend',
});

/**
 * Refreshes the access token if it expires within the next `minValidity` seconds.
 * Throws if the refresh attempt fails — callers should treat that as logout.
 */
export async function ensureFreshToken(minValiditySeconds = 30): Promise<string> {
  await keycloak.updateToken(minValiditySeconds);
  if (!keycloak.token) {
    throw new Error('Keycloak token unavailable after refresh');
  }
  return keycloak.token;
}
