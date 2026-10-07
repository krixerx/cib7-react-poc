# The core MCP sidecar image, with no service pack: a pack's image is a thin
# layer FROM this one that adds its specs folder (manifests, training
# markdown) and its engine form schemas (packs/reference/docker/mcp.Dockerfile).
# Without them the sidecar starts with no services.
#
# Build context is the repo root (see docker-compose.yml) so the COPY can
# reach the core's shared schema definitions in cib7/. Node 24 has native fetch + WebStreams.
# We run TypeScript directly via tsx — no tsc build step for this POC.
FROM node:24.21.0-alpine

WORKDIR /app

# Dependencies first so subsequent source-only changes don't bust this layer.
# From the lock and without devDependencies: tsx is a runtime dependency, and
# the test/lint toolchain has no business in the image the scanner judges.
# npm, corepack and yarn are then removed: nothing runs them at runtime, and
# their bundled packages (tar, undici, ...) were the image's remaining CVEs.
COPY mcp/package.json mcp/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund \
  && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
            /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
            /usr/local/bin/yarn /usr/local/bin/yarnpkg /root/.npm

COPY mcp/tsconfig.json ./
COPY mcp/src ./src

# The core's shared value definitions, which the pack's form schemas $ref.
# The pack's layer adds /app/services-spec (its specs folder: manifests and
# training markdown) and /app/pack-engine/processes (its engine form schemas,
# the value rules), so an agent is refused exactly what the engine would refuse.
COPY cib7/src/main/resources/schemas/core-v1.json /app/core-schemas/core-v1.json

ENV PORT=8090
ENV SERVICES_SPEC_DIR=/app/services-spec
ENV ENGINE_PROCESSES_DIR=/app/pack-engine/processes
ENV CORE_SCHEMA_FILE=/app/core-schemas/core-v1.json
EXPOSE 8090

# The stock `node` user (uid 1000) — no image we build runs as root
# (docs/security.md rule 11). Everything above is root-owned and read-only to it.
USER node

# What `npm start` ran (`tsx src/server.ts`), without npm, which is gone.
CMD ["node", "--import", "tsx", "src/server.ts"]
