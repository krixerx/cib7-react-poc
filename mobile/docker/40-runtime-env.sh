#!/bin/sh
# Runs via nginx's /docker-entrypoint.d/ hook before nginx starts. Writes
# /mobile/env.js from the container's environment so one published image serves
# any host — same pattern as frontend/docker/40-runtime-env.sh. The Flutter app
# reads window.__ENV__ (see lib/auth/auth_config.dart). Unset vars become ""; the
# app falls back to its localhost defaults.
set -e

cat > /usr/share/nginx/html/mobile/env.js <<EOF
// Generated at container start by 40-runtime-env.sh — do not edit.
window.__ENV__ = {
  KEYCLOAK_URL: "${KEYCLOAK_URL:-}",
  KEYCLOAK_REALM: "${KEYCLOAK_REALM:-}",
  KEYCLOAK_CLIENT_ID: "${KEYCLOAK_CLIENT_ID:-}",
};
EOF
