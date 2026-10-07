# The MCP sidecar image for this pack: the core sidecar image plus the pack's
# specs folder (per-service manifests and training markdown, the services
# index) and its engine form schemas, which hold the value rules the sidecar
# validates with. Data only; a core release reaches this pack by bumping
# MCP_CORE.
#
# Build context is the pack directory; mcp.Dockerfile.dockerignore keeps
# only the specs folder and the engine processes in it.
ARG MCP_CORE=docker.io/krixerx/cib7-poc-mcp-core:latest
FROM ${MCP_CORE}
COPY docs/business/services/ /app/services-spec/
COPY engine/processes/ /app/pack-engine/processes/
