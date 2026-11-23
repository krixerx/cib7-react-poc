# The backend image for this pack: the core backend image plus the pack's
# files in /opt/services, which the core puts on the classpath (loader.path):
# its registry/ descriptors and db/registry/ migrations, consent/, payment/,
# documents.json and pack.yaml (the backend refuses a pack built for another
# platform API). Data only, root-owned and read-only to the app user; a core
# release reaches this pack by bumping BACKEND_CORE.
#
# Build context is the pack directory; backend.Dockerfile.dockerignore keeps
# only backend/ and pack.yaml in it.
ARG BACKEND_CORE=docker.io/krixerx/cib7-poc-backend-core:latest
FROM ${BACKEND_CORE}
COPY backend/ /opt/services/
COPY pack.yaml /opt/services/pack.yaml
