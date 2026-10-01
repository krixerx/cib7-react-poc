#!/bin/sh
# Runs automatically via nginx's /docker-entrypoint.d/ hook before nginx
# starts. Two jobs, both because one published image must serve any hostname
# without a rebuild:
#
# 1. Overwrites the placeholder /env.js (see frontend/public/env.js) with the
#    Keycloak config from the container's environment. Unset vars are written
#    as "" on purpose: keycloak.ts treats empty strings as "unset" and falls
#    through to build-time VITE_* values, then defaults.
# 2. Writes /etc/nginx/snippets/headers-spa.conf, whose Content-Security-Policy
#    has to name the Keycloak origin (token calls, login redirects) and the
#    public S3 origin (presigned uploads go straight from the browser to
#    RustFS). The defaults match the SPA's own localhost defaults.
#
# Runs as the unprivileged nginx user; the Dockerfile makes both target files
# writable for it.
set -e

cat > /usr/share/nginx/html/env.js <<EOF
// Generated at container start by 40-runtime-env.sh — do not edit.
window.__ENV__ = {
  KEYCLOAK_URL: "${KEYCLOAK_URL:-}",
  KEYCLOAK_REALM: "${KEYCLOAK_REALM:-}",
  KEYCLOAK_CLIENT_ID: "${KEYCLOAK_CLIENT_ID:-}",
};
EOF

# scheme://host[:port] of a URL; anything that is not an http(s) URL yields
# nothing, so a malformed value cannot inject extra CSP directives.
origin_of() {
  printf '%s' "$1" | sed -nE 's#^(https?://[A-Za-z0-9.-]+(:[0-9]+)?).*$#\1#p'
}

KC_ORIGIN=$(origin_of "${KEYCLOAK_URL:-http://localhost:8180}")
[ -n "$KC_ORIGIN" ] || KC_ORIGIN=http://localhost:8180
S3_ORIGIN=$(origin_of "${S3_PUBLIC_URL:-http://localhost:9000}")
[ -n "$S3_ORIGIN" ] || S3_ORIGIN=http://localhost:9000

# 'unsafe-inline' in style-src only: Emotion (MUI, TEDI) injects <style> tags
# at runtime. Scripts stay 'self' — the Vite build emits no inline script and
# env.js is a file. Google Fonts is the stylesheet index.html links.
CSP="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com; img-src 'self' data: blob: ${S3_ORIGIN}; connect-src 'self' ${KC_ORIGIN} ${S3_ORIGIN}; frame-src ${KC_ORIGIN}; worker-src 'self' blob:; manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self' ${KC_ORIGIN}; frame-ancestors 'none'"

cat > /etc/nginx/snippets/headers-spa.conf <<EOF
# Generated at container start by 40-runtime-env.sh — do not edit.
add_header Content-Security-Policy "${CSP}" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "no-referrer" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
EOF
