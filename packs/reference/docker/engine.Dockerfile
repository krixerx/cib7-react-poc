# The engine image for this pack: the core engine image plus the pack's files
# in /opt/services, which the core puts on the classpath (loader.path): its
# processes/ (BPMN, DMN, variable policies, form schemas), templates/,
# documents/, branding/ (for the PDF documents) and pack.yaml (the engine
# refuses a pack built for another platform API). Data only, root-owned and
# read-only to the app user. ENGINE_CORE names the core image; compose and
# docker-publish.yml pass the one built from the same commit.
#
# Build context is the pack directory; engine.Dockerfile.dockerignore keeps
# only engine/, branding/ and pack.yaml in it.
ARG ENGINE_CORE=docker.io/krixerx/cib7-poc-cib7-core:latest
FROM ${ENGINE_CORE}
COPY engine/ /opt/services/
COPY branding/ /opt/services/branding/
COPY pack.yaml /opt/services/pack.yaml
