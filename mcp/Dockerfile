# Build context is the repo root (see docker-compose.yml) so the COPY
# instructions can reach docs/business/services/ for the LLM-facing
# manifests and training markdown. Node 20 has native fetch + WebStreams.
# We run TypeScript directly via tsx — no tsc build step for this POC.
FROM node:20.20.2-alpine

WORKDIR /app

# Dependencies first so subsequent source-only changes don't bust this layer.
COPY mcp/package.json ./
RUN npm install --include=dev

COPY mcp/tsconfig.json ./
COPY mcp/src ./src

# LLM-facing per-service artifacts (manifests + training md). Hand-written
# placeholders for vehicleRegistration during T6; /service-builder regenerates
# them in T14.
COPY docs/business/services /app/services-spec

ENV PORT=8090
ENV SERVICES_SPEC_DIR=/app/services-spec
EXPOSE 8090

# The stock `node` user (uid 1000) — no image we build runs as root
# (docs/security.md rule 11). Everything above is root-owned and read-only to it.
USER node

CMD ["npm", "start"]
