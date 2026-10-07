# The portal image for this pack: the core frontend image plus the pack's
# data under /pack/, which the SPA reads at startup (catalog.json and
# locales/), per task (forms/<id>.json) and before the first render
# (branding/). Data only: no build step, nothing of the core changes, so a
# core release reaches this pack by bumping FRONTEND_CORE.
#
# Build context is the pack directory; frontend.Dockerfile.dockerignore keeps
# only frontend/ and branding/ in it.
#
#   docker build -f docker/frontend.Dockerfile \
#     --build-arg FRONTEND_CORE=docker.io/krixerx/cib7-poc-frontend-core:<tag> .
ARG FRONTEND_CORE=docker.io/krixerx/cib7-poc-frontend-core:latest
FROM ${FRONTEND_CORE}
COPY frontend/ /usr/share/nginx/html/pack/
COPY branding/ /usr/share/nginx/html/pack/branding/
