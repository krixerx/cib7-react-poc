# --- build stage: Flutter web --------------------------------------------
# Self-contained, like frontend/Dockerfile's npm build. The cirruslabs image
# ships the Flutter SDK, so the web app is built entirely inside Docker — no
# Flutter install on any developer machine or CI runner, just Docker itself.
# Pinned to a Flutter release rather than the moving `stable` tag, so a
# rebuild of the same commit produces the same app.
FROM ghcr.io/cirruslabs/flutter:3.44.0 AS build
WORKDIR /app

# Bring in the committed source (lib/ + analysis options). pubspec is copied
# AFTER `flutter create` so our dependency list wins over the generated one.
COPY lib ./lib
COPY analysis_options.yaml ./

# Generate the platform scaffolding we support (web is built now; android/ios
# are kept as targets for a later native build on a Mac). `flutter create`
# leaves existing files — including our lib/main.dart — untouched.
RUN flutter create --platforms web,android,ios \
      --project-name cib7_applicant --org com.cib7 .

# Now overwrite the generated pubspec with ours (adds the http dependency),
# resolve, and build. --base-href /mobile/ makes every asset URL resolve under
# the Traefik PathPrefix(`/mobile`) route on the server.
COPY pubspec.yaml ./
# --no-web-resources-cdn bundles CanvasKit locally and points the app at it
# instead of https://www.gstatic.com/flutter-canvaskit — so the app is fully
# self-contained and renders even where that CDN is blocked/slow (and on the
# server + iPhone test, with no external dependency).
RUN flutter pub get \
 && flutter build web --release --base-href /mobile/ --no-web-resources-cdn

# Runtime Keycloak config: load env.js (written at container start) before the
# Flutter bootstrap, so window.__ENV__ is set when the app reads it. A default
# empty env.js keeps local `flutter run` working without the entrypoint.
RUN sed -i 's#</head>#  <script src="env.js"></script>\n</head>#' \
      build/web/index.html \
 && printf 'window.__ENV__ = {};\n' > build/web/env.js

# --- runtime stage: nginx (mirrors frontend) -----------------------------
# Files are served physically under /mobile/ (matching --base-href /mobile/),
# so the app resolves the same whether reached directly on the container's port
# (local dev) or via Traefik PathPrefix(`/mobile`) on the server — no prefix
# stripping needed in either path.
# nginx-unprivileged runs as uid 101 and listens on 8080 (docs/security.md
# rule 11: no image we build runs as root).
FROM nginxinc/nginx-unprivileged:1.30.5-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/snippets/ /etc/nginx/snippets/
COPY --from=build /app/build/web /usr/share/nginx/html/mobile
# Regenerates /mobile/env.js and the CSP snippet from KEYCLOAK_* / S3_PUBLIC_URL
# at container start.
COPY docker/40-runtime-env.sh /docker-entrypoint.d/40-runtime-env.sh
USER root
# Pull Alpine security fixes newer than the base tag (Trivy blocks publishing
# on fixable HIGH/CRITICAL findings, e.g. pcre2 CVE-2026-103111).
# The script runs as uid 101 at start, so the files it rewrites must be
# writable by it; running it once here bakes localhost defaults.
RUN apk upgrade --no-cache \
 && chmod 755 /docker-entrypoint.d/40-runtime-env.sh \
 && /docker-entrypoint.d/40-runtime-env.sh \
 && chown 101:0 /usr/share/nginx/html/mobile/env.js /etc/nginx/snippets /etc/nginx/snippets/headers-app.conf
USER 101
EXPOSE 8080
