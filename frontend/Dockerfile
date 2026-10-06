# Build context is the repo root (see docker-compose.yml): the image serves the
# reference service pack's form definitions from packs/reference/frontend.
# frontend/Dockerfile.dockerignore limits the context to frontend/ and packs/.

# --- build stage ---------------------------------------------------------
# Node 24, matching quality.yml. The lock is written with npm 11, which
# Node 24 ships.
FROM node:24.21.0-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ .

# Vite reads VITE_* from process.env at build time. ARG → ENV makes the
# build args (passed by docker-compose.prod.yml from .env) visible to the
# Vite build. Unset values fall back to the localhost defaults baked into
# frontend/src/auth/keycloak.ts, which is correct for local docker compose.
ARG VITE_KEYCLOAK_URL
ARG VITE_KEYCLOAK_REALM
ARG VITE_KEYCLOAK_CLIENT_ID
ENV VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL}
ENV VITE_KEYCLOAK_REALM=${VITE_KEYCLOAK_REALM}
ENV VITE_KEYCLOAK_CLIENT_ID=${VITE_KEYCLOAK_CLIENT_ID}

RUN npm run build

# --- runtime stage -------------------------------------------------------
# nginx-unprivileged runs as uid 101 and listens on 8080 (docs/security.md
# rule 11: no image we build runs as root).
FROM nginxinc/nginx-unprivileged:1.30.5-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/docker/snippets/ /etc/nginx/snippets/
COPY --from=build /app/dist /usr/share/nginx/html
# The service pack's form definitions, fetched by the schema form renderer as
# /pack/forms/<id>.json. Data only; a customer pack replaces this directory
# (a bind mount or a thin image layer) without rebuilding the SPA.
COPY packs/reference/frontend/ /usr/share/nginx/html/pack/
# Runtime config injection: regenerates /env.js and the SPA's CSP snippet from
# KEYCLOAK_URL / KEYCLOAK_REALM / KEYCLOAK_CLIENT_ID / S3_PUBLIC_URL at
# container start, so the image published to Docker Hub works on any hostname
# without a rebuild. nginx's stock entrypoint runs every executable in
# /docker-entrypoint.d/.
COPY frontend/docker/40-runtime-env.sh /docker-entrypoint.d/40-runtime-env.sh
USER root
# Pull Alpine security fixes newer than the base tag (Trivy blocks publishing
# on fixable HIGH/CRITICAL findings, e.g. pcre2 CVE-2026-103111).
# The entrypoint script runs as uid 101, so the two files it rewrites must be
# writable by it. Running it once here also bakes a localhost-default
# headers-spa.conf, so nginx starts even if the script were skipped.
RUN apk upgrade --no-cache \
 && chmod 755 /docker-entrypoint.d/40-runtime-env.sh \
 && /docker-entrypoint.d/40-runtime-env.sh \
 && chown 101:0 /usr/share/nginx/html/env.js /etc/nginx/snippets /etc/nginx/snippets/headers-spa.conf
USER 101
EXPOSE 8080
