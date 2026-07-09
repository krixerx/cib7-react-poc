# Deploying cib7-react-poc

This guide is for the **administrator deploying the application**. You do
not need the source code, a Java/Node toolchain, or any build step — all
seven application images are pre-built and published to
[Docker Hub](https://hub.docker.com/u/krixerx) on every change. Everything
you need is in this `deploy/` folder:

```
deploy/
├── docker-compose.yml                 the whole stack, pull-only
├── deploy.sh                          one-command upgrade + smoke test
├── .env.example                       configuration template
├── keycloak/realm-export.json         users, roles, OAuth clients
└── traefik/dynamic/tls.yml.example    TLS config (only for the HTTPS setup)
```

**Contents**

1. [Prerequisites](#prerequisites)
2. [Getting the deploy folder](#getting-the-deploy-folder)
3. [Quick start — single machine](#quick-start--single-machine)
4. [Configuration reference](#configuration-reference)
5. [Deploying with real hostnames + TLS](#deploying-with-real-hostnames--tls)
6. [Day-2 operations](#day-2-operations)
7. [Known limitations (this is a POC)](#known-limitations-this-is-a-poc)
8. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Docker ≥ 24 with the Compose plugin** — `docker compose version` must
  work (the standalone `docker-compose` binary is not tested).
- ~4 GB free RAM for the stack (two JVMs, Keycloak, headless Chromium).
- Free host ports — single-machine mode: **3000** (app), **3001** (mobile
  app direct door), **8180** (Keycloak), **9000** (object storage),
  optionally 8025 (mail inbox). TLS mode additionally: **80** and **443**.

No git, no JDK, no Node.

## Getting the deploy folder

Download just this folder — no repository clone needed:

```bash
curl -L https://github.com/krixerx/cib7-react-poc/archive/refs/heads/main.tar.gz \
  | tar xz --strip-components=1 "cib7-react-poc-main/deploy"
cd deploy
```

(Or download the repo ZIP from GitHub and keep only `deploy/`.)

## Quick start — single machine

For evaluating the POC on one machine where the browser runs on (or can
reach) the Docker host as `localhost`. **No configuration needed** — all
defaults target localhost and match the shipped Keycloak realm:

```bash
docker compose up -d
```

First start pulls ~2 GB of images and takes a couple of minutes; the
process engine intentionally waits for Keycloak to become healthy. Watch
with `docker compose logs -f` until things go quiet, then open
**<http://localhost:3000>**.

### Demo logins

| Who | URL | Login |
|---|---|---|
| Applicant (citizen) | <http://localhost:3000> | `bart` / `bart` |
| Civil servant (case worker) | <http://localhost:3000> | `homer` / `homer` |
| Mobile applicant app (Flutter, POC) | <http://localhost:3000/mobile/> | `bart` / `bart` |
| CIB seven Cockpit / Tasklist / Admin | <http://localhost:3000/camunda/> | `admin` / `admin` |
| Keycloak admin console | <http://localhost:8180/admin/> | `admin` / `admin` |
| Process-sent emails (Mailpit) | <http://localhost:8025> — needs `docker compose --profile mail up -d mailpit-ui` | — |
| MCP endpoint for AI clients | `http://localhost:3000/mcp` | OAuth2 (browser pops; log in as `bart`) |

The emails the processes send (approvals, owner confirmations with
clickable links, reminders) never leave the machine — they land in the
Mailpit inbox above. Enable it for any end-to-end walkthrough.

### Smoke test

```bash
curl -s  http://localhost:3000/engine-rest/engine          # → [{"name":"default"}]
curl -s  http://localhost:3000/api/public/vehicle-registry/vehicles | head -c 200   # → JSON
curl -sI http://localhost:8180/realms/cib7-poc/.well-known/openid-configuration     # → 200
```

Then in a browser: log in as `bart`, start a *Vehicle registration*, and
the case appears under *My processes*.

## Configuration reference

All knobs live in one file. To change anything:

```bash
cp .env.example .env
# edit, then:
docker compose up -d        # recreates only the affected containers
```

| Variable | Default | What it does |
|---|---|---|
| `IMAGE_TAG` | `latest` | Docker Hub tag for the six app images. Pin a commit SHA for reproducible deploys. |
| `PUBLIC_KEYCLOAK_URL` | `http://localhost:8180` | Browser-visible Keycloak URL. Stamped into every JWT — must match the address bar exactly. |
| `PUBLIC_FRONTEND_URL` | `http://localhost:3000` | Browser-visible app URL. Embedded in confirmation-email links. |
| `PUBLIC_S3_URL` | `http://localhost:9000` | Browser-visible object-storage URL (presigned upload/download links). |
| `MCP_RESOURCE_URL` | `${PUBLIC_FRONTEND_URL}/mcp` | Where AI clients reach the MCP sidecar. |
| `FRONTEND_HTTP_PORT` | `3000` | Host port (or `ip:port` binding) for the plain-HTTP front door. |
| `MOBILE_HTTP_PORT` | `3001` | Same, for the mobile app's direct door (it is also served at `/mobile` through the front door and Traefik). |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Keycloak bootstrap admin (first start only). |
| `KEYCLOAK_BACKEND_CLIENT_SECRET` | dev value | OAuth client secret, must mirror `keycloak/realm-export.json`. |
| `KEYCLOAK_WEBAPPS_CLIENT_SECRET` | dev value | Same, for the Cockpit/Tasklist SSO client. |
| `KEYCLOAK_BUSINESS_CLIENT_SECRET` | dev value | Same, for the backend's service account. |
| `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` | dev values | Object-storage root credentials. |
| `INTERNAL_TASK_TOKEN` | dev value | Shared secret for the internal document-write calls; the integration bus (`esb`) injects it as `X-Internal-Token`, the backend verifies it. |

> **Rule of thumb:** the three `KEYCLOAK_*_CLIENT_SECRET` values and the
> demo users live in **two places** — `.env` *and*
> `keycloak/realm-export.json`. They must agree, and the realm file is
> only read on the **first** start (see
> [Re-importing the realm](#re-importing-the-realm)).

## Deploying with real hostnames + TLS

For a server where browsers connect via DNS names instead of localhost.
The same images are used — the frontend reads its Keycloak URL at
container start, so nothing is rebuilt.

You need:

- **DNS**: three names pointing at the host — the app
  (`app.example.com`), Keycloak (`kc.example.com`), and object storage
  (`s3.example.com`).
- **TLS certificate + key** covering the app hostname (a SAN cert or
  separate certs; see `traefik/dynamic/tls.yml.example` for multi-cert
  setup). The bundled Traefik does **not** do Let's Encrypt — certs are
  files you supply.

### 1. Edit `keycloak/realm-export.json` (before first start!)

Replace localhost URLs and dev secrets in three clients:

```jsonc
// cib7-frontend (the SPA)
"redirectUris": ["https://app.example.com/*"],
"webOrigins":   ["https://app.example.com"],
"attributes": { "post.logout.redirect.uris": "https://app.example.com/*" }

// cib7-webapps (Cockpit/Tasklist/Admin SSO)
"secret": "<your KEYCLOAK_WEBAPPS_CLIENT_SECRET>",
"redirectUris": [
  "https://app.example.com/login/oauth2/code/keycloak",
  "https://app.example.com/camunda/*"
],
"webOrigins": ["https://app.example.com"],
"attributes": { "post.logout.redirect.uris": "https://app.example.com/*" }

// cib7-mobile (the Flutter applicant app, served under /mobile)
"redirectUris": ["https://app.example.com/mobile/*"],
"webOrigins":   ["https://app.example.com"],
"attributes": { ..., "post.logout.redirect.uris": "https://app.example.com/mobile/*" }

// cib7-backend          → "secret": "<your KEYCLOAK_BACKEND_CLIENT_SECRET>"
// cib7-business         → "secret": "<your KEYCLOAK_BUSINESS_CLIENT_SECRET>"
```

Generate secrets with `openssl rand -base64 36`. Consider removing the
demo users (`bart`, `homer` — username equals password) before exposing
the host.

### 2. Write `.env`

```bash
cp .env.example .env
```

Set **all** of: `PUBLIC_KEYCLOAK_URL`, `PUBLIC_FRONTEND_URL`,
`PUBLIC_S3_URL`, `KEYCLOAK_ADMIN_PASSWORD`, the three
`KEYCLOAK_*_CLIENT_SECRET` values (same as step 1), `RUSTFS_ACCESS_KEY`,
`RUSTFS_SECRET_KEY`, `INTERNAL_TASK_TOKEN`. Also bind the plain-HTTP port
to loopback so only Traefik is internet-facing:

```
FRONTEND_HTTP_PORT=127.0.0.1:3000
```

### 3. Drop in the TLS certificate

```bash
cp traefik/dynamic/tls.yml.example traefik/dynamic/tls.yml
$EDITOR traefik/dynamic/tls.yml                  # point at your cert filenames

cp /path/to/cert.crt traefik/certs/app.example.com.crt
cp /path/to/key.key  traefik/certs/app.example.com.key
chmod 644 traefik/certs/*.crt
chmod 600 traefik/certs/*.key
```

Cert rotation later is hot-reload: replace the files, Traefik picks them
up in seconds — no restart.

### 4. Start

```bash
docker compose --profile tls up -d
```

Traefik terminates TLS on :443 ( :80 just redirects) and routes the app,
the engine APIs, the Camunda webapps, `/mobile`, and `/mcp` under
`https://app.example.com`.

**Keycloak and object storage need their own TLS.** They are *not* behind
the bundled Traefik:

- Keycloak listens plain-HTTP on host port 8180. Put your TLS terminator
  (nginx, Caddy, a cloud LB) in front of it for `kc.example.com`. It
  already trusts `X-Forwarded-*` headers.
- RustFS listens on host port 9000. Front it the same way for
  `s3.example.com` — browsers hit it directly with presigned URLs, and it
  is deliberately not proxied through Traefik (S3 request signatures
  break if a proxy rewrites the host or path).

### 5. Verify

| Check | Expected |
|---|---|
| `curl -sI http://app.example.com/` | `308` redirect to `https://…` |
| `curl -sI https://app.example.com/` | `200` |
| `curl -s https://app.example.com/engine-rest/engine` | `[{"name":"default"}]` |
| `curl -sI https://kc.example.com/realms/cib7-poc/.well-known/openid-configuration` | `200`, issuer = `https://kc.example.com/realms/cib7-poc` |
| Browser: `https://app.example.com` | Redirects to Keycloak login, then back into the app |
| AI client at `https://app.example.com/mcp` | OAuth pop, then tools list |

## Day-2 operations

### Logs

```bash
docker compose logs -f                # everything
docker compose logs -f cib7           # process engine
docker compose logs -f backend        # business API
docker compose logs -f keycloak       # auth
docker compose logs -f mcp            # AI sidecar
tail -F traefik/logs/access.log       # ingress access log (TLS mode)
```

### Upgrading — the deploy script

`deploy.sh` runs the whole upgrade on the host in one command: refreshes
the bundle from git (when it is a clone), backs up the document volume,
pulls images, restarts what changed, and smoke-tests the public endpoints:

```bash
./deploy.sh                  # interactive confirmation
./deploy.sh --yes            # non-interactive (cron / ssh one-liner)
./deploy.sh --realm          # also recreate Keycloak to re-import an
                             # edited realm-export.json (drops runtime users)
```

Set `COMPOSE_PROFILES=tls` in `.env` once and every compose command —
including the script's — picks the TLS profile up automatically;
otherwise pass `--profile tls`. From an admin PC the upgrade is then:

```bash
ssh root@your-server /opt/…/deploy/deploy.sh --yes
```

If you hold per-host edits on tracked files via `git update-index
--skip-worktree`, the script refuses to pull when upstream changed those
files and asks for a hand-merge — it will never silently clobber or
silently keep stale config.

Manual equivalent:

```bash
docker compose pull
docker compose up -d            # add --profile tls in the TLS setup
```

If you pinned `IMAGE_TAG`, change it in `.env` first.
**Warning:** recreating the engine container wipes all process state —
see [Known limitations](#known-limitations-this-is-a-poc).

### Re-importing the realm

Keycloak imports `keycloak/realm-export.json` **once**, on first start.
Restarting is not enough to pick up edits — recreate the container:

```bash
docker compose rm -sf keycloak
docker compose up -d
```

### What persists, what doesn't

| Data | Where | Survives restart? |
|---|---|---|
| Uploaded documents + generated PDFs | named volume `rustfs-data` | **yes** |
| Process instances, tasks, history | engine's in-memory H2 | **no** |
| Keycloak users created at runtime | Keycloak's dev H2 | **no** (realm re-imported from JSON) |
| Emails | Mailpit (in-memory) | no |

To back up the one persistent piece:
`docker run --rm -v deploy_rustfs-data:/data -v "$PWD":/backup alpine tar czf /backup/rustfs-backup.tgz /data`

## Known limitations (this is a POC)

This stack demonstrates the architecture; it is **not hardened for
production traffic**. The big ones an administrator must know:

- **No durable database.** The process engine, the backend, and Keycloak
  all run on in-memory/dev databases. Every process instance, task, and
  runtime-created user account is lost when the respective container is
  recreated. Process and decision definitions are re-deployed
  automatically on startup, so the *application* always comes back — the
  *cases* don't.
- **Mail goes to Mailpit only.** The processes talk Mailpit's HTTP API,
  not standard SMTP — pointing them at a real mail server requires
  changes to the process definitions, not just configuration.
- **Demo users ship enabled** (`bart`/`bart`, `homer`/`homer`,
  `admin`/`admin` in two places). Remove or change them before exposing
  the stack.
- **All secrets fall back to dev defaults silently.** Forgetting a value
  in `.env` does not fail the startup — it falls back to a publicly
  known default. Double-check `.env` on anything internet-reachable.

A longer engineering-oriented gap list lives in the repository at
[`docs/deployment.md`](https://github.com/krixerx/cib7-react-poc/blob/main/docs/deployment.md).

## Troubleshooting

**Login works but every page says 401 / "session expired".**
`iss` mismatch — `PUBLIC_KEYCLOAK_URL` must be byte-for-byte what the
browser shows during login (scheme, host, port, no trailing slash). Fix
`.env`, `docker compose up -d`, and hard-refresh the browser.

**Keycloak shows "Client not found" or redirects to a localhost URL.**
You edited `keycloak/realm-export.json` *after* the first start. The
import runs once — recreate Keycloak:
`docker compose rm -sf keycloak && docker compose up -d`.

**The app loads but the login page is `http://localhost:8180/...` on a
remote server.** The frontend container didn't get `PUBLIC_KEYCLOAK_URL`.
Confirm it's set in `.env` and recreate: `docker compose up -d frontend`.
Verify with `curl -s http://localhost:3000/env.js` — it must show your
Keycloak URL.

**`docker compose up` hangs on the engine.** Normal for the first minute:
the engine waits for Keycloak's health check. If it loops longer, check
`docker compose logs keycloak` — usually a malformed edit to
`realm-export.json`.

**Document upload fails in the browser.** The browser must be able to
reach `PUBLIC_S3_URL` directly (default `http://localhost:9000`). On a
remote server this needs the `s3.` hostname + TLS terminator described
above; check the browser dev-tools network tab for the failing presigned
URL.

**Port already in use.** Single-machine mode binds 3000, 3001, 8180, 9000
(+ 8025 with the `mail` profile); TLS mode adds 80/443. Move the app ports
with `FRONTEND_HTTP_PORT` / `MOBILE_HTTP_PORT`; the others are fixed in
`docker-compose.yml`.
