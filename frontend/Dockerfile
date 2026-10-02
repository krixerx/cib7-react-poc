# --- build stage ---------------------------------------------------------
# Node 24, matching quality.yml: TEDI's engines require node >=24 and npm >=11,
# and the lock is written with npm 11.
FROM node:24.21.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .

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
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/snippets/ /etc/nginx/snippets/
COPY --from=build /app/dist /usr/share/nginx/html
# Runtime config injection: regenerates /env.js and the SPA's CSP snippet from
# KEYCLOAK_URL / KEYCLOAK_REALM / KEYCLOAK_CLIENT_ID / S3_PUBLIC_URL at
# container start, so the image published to Docker Hub works on any hostname
# without a rebuild. nginx's stock entrypoint runs every executable in
# /docker-entrypoint.d/.
COPY docker/40-runtime-env.sh /docker-entrypoint.d/40-runtime-env.sh
USER root
# The entrypoint script runs as uid 101, so the two files it rewrites must be
# writable by it. Running it once here also bakes a localhost-default
# headers-spa.conf, so nginx starts even if the script were skipped.
RUN chmod 755 /docker-entrypoint.d/40-runtime-env.sh \
 && /docker-entrypoint.d/40-runtime-env.sh \
 && chown 101:0 /usr/share/nginx/html/env.js /etc/nginx/snippets /etc/nginx/snippets/headers-spa.conf
USER 101
EXPOSE 8080
