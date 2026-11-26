# The Patchbay image for this pack: the core Patchbay image (mail-send and
# pdf-render) plus the pack's own integration packages. Each folder
# patchbay/<name>/ (the unpacked content of a Patchbay console Export) is
# zipped to /import/50-<name>.zip, so the pack's packages import after the
# core's 10-/20- ones. This pack has none of its own: patchbay/ holds only
# .gitkeep and the layer adds nothing (docs/platform-api.md, design D8 of
# replace-esb-with-patchbay). PATCHBAY_CORE names the core image; compose and
# docker-publish.yml pass the one built from the same commit.
#
# Build context is the pack directory; patchbay.Dockerfile.dockerignore keeps
# only patchbay/ in it.
ARG PATCHBAY_CORE=docker.io/krixerx/cib7-poc-patchbay:latest

FROM alpine:3.24.4 AS packages
RUN apk add --no-cache zip
COPY patchbay/ /src/
# zip -X leaves out extra file attributes; -D leaves out directory entries.
# The guard keeps an empty patchbay/ (the literal */) from failing the loop.
RUN mkdir -p /import \
 && cd /src \
 && for d in */; do \
      [ -d "$d" ] || continue; \
      name="${d%/}"; \
      (cd "$name" && zip -qrXD "/import/50-${name}.zip" .); \
    done

FROM ${PATCHBAY_CORE}
COPY --from=packages /import/ /import/
