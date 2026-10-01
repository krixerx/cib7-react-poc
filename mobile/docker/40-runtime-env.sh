#!/bin/sh
# Runs via nginx's /docker-entrypoint.d/ hook before nginx starts, as the
# unprivileged nginx user. Two jobs, so one published image serves any host:
#
# 1. Writes /mobile/env.js from the container's environment — same pattern as
#    frontend/docker/40-runtime-env.sh. The Flutter app reads window.__ENV__
#    (see lib/auth/auth_config.dart). Unset vars become ""; the app falls back
#    to its localhost defaults.
# 2. Writes /etc/nginx/snippets/headers-app.conf, whose Content-Security-Policy
#    names the Keycloak origin the app calls for tokens.
set -e

cat > /usr/share/nginx/html/mobile/env.js <<EOT
// Generated at container start by 40-runtime-env.sh — do not edit.
window.__ENV__ = {
  KEYCLOAK_URL: "${KEYCLOAK_URL:-}",
  KEYCLOAK_REALM: "${KEYCLOAK_REALM:-}",
  KEYCLOAK_CLIENT_ID: "${KEYCLOAK_CLIENT_ID:-}",
};
EOT

# scheme://host[:port] of a URL; anything else yields nothing, so a malformed
# value cannot inject extra CSP directives.
origin_of() {
  printf '%s' "$1" | sed -nE 's#^(https?://[A-Za-z0-9.-]+(:[0-9]+)?).*$#\1#p'
}

KC_ORIGIN=$(origin_of "${KEYCLOAK_URL:-http://localhost:8180}")
[ -n "$KC_ORIGIN" ] || KC_ORIGIN=http://localhost:8180
S3_ORIGIN=$(origin_of "${S3_PUBLIC_URL:-http://localhost:9000}")
[ -n "$S3_ORIGIN" ] || S3_ORIGIN=http://localhost:9000

# Flutter web specifics: 'wasm-unsafe-eval' lets CanvasKit's WebAssembly
# compile (no JS eval is allowed), the engine injects <style> elements, and its
# text fallback fetches Noto fonts from fonts.gstatic.com at runtime.
CSP="default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data: https://fonts.gstatic.com; img-src 'self' data: blob: ${S3_ORIGIN}; connect-src 'self' ${KC_ORIGIN} ${S3_ORIGIN} https://fonts.gstatic.com; worker-src 'self' blob:; manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self' ${KC_ORIGIN}; frame-ancestors 'none'"

cat > /etc/nginx/snippets/headers-app.conf <<EOT
# Generated at container start by 40-runtime-env.sh — do not edit.
add_header Content-Security-Policy "${CSP}" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "no-referrer" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
EOT
