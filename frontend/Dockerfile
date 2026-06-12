# --- build stage ---------------------------------------------------------
FROM node:20-alpine AS build
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
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Runtime config injection: regenerates /env.js from KEYCLOAK_URL /
# KEYCLOAK_REALM / KEYCLOAK_CLIENT_ID env vars at container start, so the
# image published to Docker Hub works on any hostname without a rebuild.
# nginx's stock entrypoint runs every executable in /docker-entrypoint.d/.
COPY docker/40-runtime-env.sh /docker-entrypoint.d/40-runtime-env.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-env.sh
EXPOSE 80
