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
# 3. Fills the web shell's brand placeholders (index.html, manifest.json) from
#    the service pack's branding under /mobile/pack/branding/, which the pack's
#    image layer adds: a browser reads the page title and the install manifest
#    before the app runs, so these cannot wait for lib/pack.dart. Same rules
#    as the app: a value outside brand format v1, or no pack at all, keeps the
#    core default.
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

SHELL_DIR=/usr/share/nginx/mobile-shell
WEB=/usr/share/nginx/html/mobile
BRANDING="$WEB/pack/branding"

# The portal name: a non-blank string of at most 60 characters.
NAME=$(jq -r 'if (.name | type) == "string" then (.name | gsub("^\\s+|\\s+$"; "")) else "" end
  | if length > 0 and length <= 60 then . else "" end' \
  "$BRANDING/locales/en/brand.json" 2>/dev/null) || NAME=""
[ -n "$NAME" ] || NAME=eRegistrations
# The light scheme's primary colour, hex only.
COLOR=$(jq -r 'if .version == 1 and (.light | type) == "object" and (.light.primary | type) == "string"
  and (.light.primary | test("^#[0-9a-fA-F]{6}$")) then .light.primary else "" end' \
  "$BRANDING/tokens.json" 2>/dev/null) || COLOR=""
[ -n "$COLOR" ] || COLOR="#0b57c9"

# jq does the replacing, so no character of the name can break out: HTML
# escaping in the page, JSON encoding in the manifest.
jq -Rrs --arg n "$NAME" --arg c "$COLOR" \
  '($n | gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;") | gsub("\""; "&quot;")) as $h
   | gsub("__BRAND_NAME__"; $h) | gsub("__BRAND_COLOR__"; $c)' \
  "$SHELL_DIR/index.html" > "$WEB/index.html"
jq -Rrs --arg n "$NAME" --arg c "$COLOR" \
  '($n | tojson) as $j
   | gsub("\"__BRAND_NAME__\""; $j) | gsub("__BRAND_NAME__"; $j[1:-1]) | gsub("__BRAND_COLOR__"; $c)' \
  "$SHELL_DIR/manifest.json" > "$WEB/manifest.json"
