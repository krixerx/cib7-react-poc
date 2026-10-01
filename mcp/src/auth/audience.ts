/**
 * The audience every token accepted by the sidecar must carry. Same env var
 * and default as `rest.security.required-audience` in the engine and the
 * backend's resource-server config, so one setting moves all three. Lives in
 * its own module so the invitation predicate can share it without importing
 * the JWKS resolver in verify.ts.
 */
export const REQUIRED_AUDIENCE = process.env.KEYCLOAK_REST_AUDIENCE || 'cib7-rest-api';
