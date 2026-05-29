# Node 20 has native fetch + FormData + Blob, so server.js needs nothing
# beyond express. Alpine keeps the final image under 60 MB.
FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

ENV PORT=8088
EXPOSE 8088

CMD ["node", "server.js"]
