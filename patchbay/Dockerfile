# Core Patchbay image (cib7-poc-patchbay): the pinned upstream release plus the
# core packages (mail-send, pdf-render), imported at every start. It holds
# nothing of a pack; a pack layers its own packages on top
# (<pack>/docker/patchbay.Dockerfile, FROM this image).
#
# Packages live in git unpacked (packages/<nn>-<name>/, the content of a
# console Export) and are zipped here, so the import order is the folder
# names. See README.md and design D7 to D9 of replace-esb-with-patchbay.

FROM alpine:3.24.4 AS packages
RUN apk add --no-cache zip
COPY packages/ /src/
# zip -X leaves out extra file attributes; -D leaves out directory entries.
RUN mkdir /import \
 && cd /src \
 && for d in */; do \
      name="${d%/}"; \
      (cd "$name" && zip -qrXD "/import/${name}.zip" .); \
    done \
 && ls /import/*.zip

FROM krixerx/patchbay:0.1.0
# The upstream image already runs as mapper (uid 10001); no USER change.
COPY --from=packages /import/ /import/
ENV MAPPER_IMPORT_DIR=/import
