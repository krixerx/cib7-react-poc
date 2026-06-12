// Placeholder runtime config. Vite copies this to dist/ as-is; the Docker
// entrypoint (docker/40-runtime-env.sh) overwrites it at container start
// with values from the KEYCLOAK_URL / KEYCLOAK_REALM / KEYCLOAK_CLIENT_ID
// env vars. An empty object means "no runtime overrides" — keycloak.ts
// falls back to build-time VITE_* vars, then to the localhost defaults.
window.__ENV__ = {};
