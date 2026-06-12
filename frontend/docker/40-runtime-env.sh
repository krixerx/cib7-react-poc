#!/bin/sh
# Runs automatically via nginx's /docker-entrypoint.d/ hook before nginx
# starts. Overwrites the placeholder /env.js (see frontend/public/env.js)
# with runtime config from the container's environment, so one published
# image can serve any hostname — no rebuild with VITE_* build args needed.
#
# Unset vars are written as "" on purpose: keycloak.ts treats empty strings
# as "unset" and falls through to build-time VITE_* values, then defaults.
set -e

cat > /usr/share/nginx/html/env.js <<EOF
// Generated at container start by 40-runtime-env.sh — do not edit.
window.__ENV__ = {
  KEYCLOAK_URL: "${KEYCLOAK_URL:-}",
  KEYCLOAK_REALM: "${KEYCLOAK_REALM:-}",
  KEYCLOAK_CLIENT_ID: "${KEYCLOAK_CLIENT_ID:-}",
};
EOF
