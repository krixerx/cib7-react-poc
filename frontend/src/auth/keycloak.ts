import Keycloak from 'keycloak-js';

/**
 * Singleton Keycloak instance shared by the whole SPA.
 *
 * The defaults match the docker-compose setup (Keycloak on host port 8180,
 * realm `cib7-poc`, public client `cib7-frontend`). Override at build time
 * with `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, and `VITE_KEYCLOAK_CLIENT_ID`.
 *
 * Empty strings are treated as "unset" so the defaults still kick in when the
 * Dockerfile's `ARG VITE_KEYCLOAK_*` are left unset — `ENV VAR=${ARG}` expands
 * to `""` (not undefined), which would otherwise beat the `??` fallback and
 * make keycloak-js build `localhost:3000/realms//…?client_id=` against the
 * SPA's own origin, producing an infinite same-origin redirect loop.
 */
function envOr(name: string, fallback: string): string {
  const v = import.meta.env[name] as string | undefined;
  return v && v.length > 0 ? v : fallback;
}

export const keycloak = new Keycloak({
  url:      envOr('VITE_KEYCLOAK_URL',       'http://localhost:8180'),
  realm:    envOr('VITE_KEYCLOAK_REALM',     'cib7-poc'),
  clientId: envOr('VITE_KEYCLOAK_CLIENT_ID', 'cib7-frontend'),
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
