# Node 20 has native fetch + FormData + Blob, so server.js needs nothing
# beyond express. Alpine keeps the final image under 60 MB.
FROM node:20.20.2-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

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
