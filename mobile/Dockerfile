# --- build stage: Flutter web --------------------------------------------
# Self-contained, like frontend/Dockerfile's npm build. The cirruslabs image
# ships the Flutter SDK, so the web app is built entirely inside Docker — no
# Flutter install on any developer machine or CI runner, just Docker itself.
FROM ghcr.io/cirruslabs/flutter:stable AS build
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
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build/web /usr/share/nginx/html/mobile
# Regenerates /mobile/env.js from KEYCLOAK_* env at container start.
COPY docker/40-runtime-env.sh /docker-entrypoint.d/40-runtime-env.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-env.sh
