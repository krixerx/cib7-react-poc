# Node 24 has native fetch + FormData + Blob, so server.js needs nothing
# beyond express. Alpine keeps the final image under 60 MB.
FROM node:24.21.0-alpine

WORKDIR /app

# npm, corepack and yarn are removed once the dependencies are in: nothing
# runs them at runtime, and their bundled packages (tar, undici, ...) were the
# image's remaining CVEs.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund \
  && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
            /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
            /usr/local/bin/yarn /usr/local/bin/yarnpkg /root/.npm

# gelf.js ships the service's own logs to Graylog; server.js requires it at the
# top, so leaving it out of the image turns every start into a module-not-found
# crash loop, and a container that never runs has no DNS name on the network.
COPY server.js gelf.js ./

ENV PORT=8088
EXPOSE 8088

# The stock `node` user (uid 1000) — no image we build runs as root
# (docs/security.md rule 11).
USER node

CMD ["node", "server.js"]
