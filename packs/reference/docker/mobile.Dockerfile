# The mobile app image for this pack: the core mobile image plus the pack's
# branding (name, colours, logo) and texts (document labels) under
# /mobile/pack/, which the app reads at startup (lib/pack.dart) and the start
# script uses for the page title and install manifest. Data only; a core
# release reaches this pack by bumping MOBILE_CORE.
#
# Build context is the pack directory; mobile.Dockerfile.dockerignore keeps
# only branding/ and the frontend texts in it.
ARG MOBILE_CORE=docker.io/krixerx/cib7-poc-mobile-core:latest
FROM ${MOBILE_CORE}
COPY branding/ /usr/share/nginx/html/mobile/pack/branding/
COPY frontend/locales/ /usr/share/nginx/html/mobile/pack/locales/
