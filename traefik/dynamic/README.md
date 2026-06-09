# Traefik dynamic configuration templates

Files in this directory are **templates** — they are NOT loaded by the
running stack. The deployment host's Traefik mounts
`/opt/volumes/traefik/dynamic/` from the host filesystem (see
`docker-compose.prod.yml`), not this directory.

## Why a separate path

Two reasons to keep dynamic config on the host instead of in the image
or the repo:

1. **Hot-reload without a rebuild.** Edit a file in
   `/opt/volumes/traefik/dynamic/`, Traefik picks it up within a few
   seconds (`--providers.file.watch=true`). No `docker compose up
   --build`, no container restart.
2. **Secrets stay off Git.** Cert paths and TLS options live in the
   repo as templates; the actual cert files sit in
   `/opt/volumes/traefik/certs/` on the host, never committed.

## How to use

On the deployment host:

```bash
sudo mkdir -p /opt/volumes/traefik/{certs,dynamic,logs}
sudo chmod 700 /opt/volumes/traefik/certs           # private keys live here
sudo cp traefik/dynamic/tls.yml.example /opt/volumes/traefik/dynamic/tls.yml
sudo $EDITOR /opt/volumes/traefik/dynamic/tls.yml   # point at your actual certs
sudo cp your-cert.crt /opt/volumes/traefik/certs/app.example.com.crt
sudo cp your-key.key  /opt/volumes/traefik/certs/app.example.com.key
sudo chmod 644 /opt/volumes/traefik/certs/*.crt
sudo chmod 600 /opt/volumes/traefik/certs/*.key
```

Then `docker compose -f docker-compose.yml -f docker-compose.prod.yml up
-d` — Traefik starts, reads `dynamic/tls.yml`, and serves the cert on
`:443`.

See [`docs/deployment.md`](../../docs/deployment.md) for the full
walk-through.
