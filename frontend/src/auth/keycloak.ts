import Keycloak from 'keycloak-js';

/**
 * Singleton Keycloak instance shared by the whole SPA.
 *
 * The defaults match the docker-compose setup (Keycloak on host port 8180,
 * realm `cib7-poc`, public client `cib7-frontend`). Two override layers,
 * checked in order:
 *
 * 1. **Runtime** — `window.__ENV__`, written to `/env.js` by the container
 *    entrypoint (frontend/docker/40-runtime-env.sh) from the `KEYCLOAK_URL`
 *    / `KEYCLOAK_REALM` / `KEYCLOAK_CLIENT_ID` env vars. This is what lets
 *    one published image serve any hostname without a rebuild.
 * 2. **Build time** — `VITE_KEYCLOAK_*` Vite vars baked into the bundle.
 *
 * Empty strings are treated as "unset" at both layers so the defaults still
 * kick in when the Dockerfile's `ARG VITE_KEYCLOAK_*` are left unset —
 * `ENV VAR=${ARG}` expands to `""` (not undefined), which would otherwise
 * beat the `??` fallback and make keycloak-js build
 * `localhost:3000/realms//…?client_id=` against the SPA's own origin,
 * producing an infinite same-origin redirect loop. The entrypoint writes
 * `""` for unset vars the same way.
 */
function envOr(name: string, fallback: string): string {
  const runtimeKey = name.replace(/^VITE_/, '') as keyof NonNullable<Window['__ENV__']>;
  const runtime = window.__ENV__?.[runtimeKey];
  if (runtime && runtime.length > 0) return runtime;
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
