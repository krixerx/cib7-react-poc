# The engine image for this pack: the core engine image plus the pack's files
# in /opt/services, which the core puts on the classpath (loader.path): its
# processes/ (BPMN, DMN, variable policies, form schemas), templates/,
# documents/, branding/ (for the PDF documents) and pack.yaml (the engine
# refuses a pack built for another platform API). Data only, root-owned and
# read-only to the app user; a core release reaches this pack by bumping
# ENGINE_CORE.
#
# Build context is the pack directory; engine.Dockerfile.dockerignore keeps
# only engine/, branding/ and pack.yaml in it.
ARG ENGINE_CORE=docker.io/krixerx/cib7-poc-cib7-core:latest
FROM ${ENGINE_CORE}
COPY engine/ /opt/services/
COPY branding/ /opt/services/branding/
COPY pack.yaml /opt/services/pack.yaml
