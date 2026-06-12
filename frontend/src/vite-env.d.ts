/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_KEYCLOAK_URL?: string;
  readonly VITE_KEYCLOAK_REALM?: string;
  readonly VITE_KEYCLOAK_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// Runtime config written to /env.js by the Docker entrypoint
// (frontend/docker/40-runtime-env.sh) and loaded by index.html before the
// app bundle. Keys are the VITE_* names minus the prefix.
interface Window {
  __ENV__?: {
    KEYCLOAK_URL?: string;
    KEYCLOAK_REALM?: string;
    KEYCLOAK_CLIENT_ID?: string;
  };
}
