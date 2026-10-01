# Deployment (from source)

> **Most administrators should use [`deploy/README.md`](../deploy/README.md)
> instead** — a pull-only setup using the pre-built images CI publishes to
> Docker Hub (`krixerx/cib7-poc-*`): no clone, no build, just a compose
> file + `.env`. This document covers deploying **from source**, which you
> only need when running unpushed changes.

**When to read this:** when deploying the POC to a server other than your
laptop — anywhere browsers reach the SPA on something other than
`http://localhost:3000`. Pairs with [`architecture.md`](architecture.md)
(topology) and the [`README.md`](../README.md) (single-machine dev).

**Contents**
1. [Status — this is a POC](#status--this-is-a-poc)
2. [Prerequisites](#prerequisites)
3. [Files you touch](#files-you-touch)
4. [Step-by-step](#step-by-step)
5. [TLS termination](#tls-termination)
6. [Day-2 operations](#day-2-operations)
7. [Production gaps the overlay does not solve](#production-gaps-the-overlay-does-not-solve)

---

## Status — this is a POC

This stack is a proof of concept, not a production system. The overlay
described here makes it **runnable on a public host with real
hostnames**, but several defaults must be replaced before exposing it
to real users — see
[Production gaps the overlay does not solve](#production-gaps-the-overlay-does-not-solve).
If you are evaluating whether this is the right shape for your
production system, deploy it, but treat it as a staging/demo
environment, not as a production target.

## Prerequisites

On the target server (assumed Linux — the `/opt/volumes/...` paths
below are POSIX; Windows hosts need a different mount path):

- **Docker** ≥ 24 with the Compose plugin (`docker compose version`
  works — not the standalone `docker-compose`).
- **TLS certificate + private key** for the SPA hostname. The prod
  Traefik terminates TLS itself — no separate Caddy / nginx in front.
  Certs are loaded from `/opt/volumes/traefik/certs/`; see step 3.
  ACME / Let's Encrypt is **not** configured — extend
  `docker-compose.prod.yml` if you need auto-issuance.
- **Public DNS** for two hostnames (the prior three-host split is no
  longer needed because Traefik path-routes engine + SPA + MCP through
  a single origin):
  - SPA + engine + Cockpit/Tasklist/Admin + MCP — `app.example.com` →
    host `:443` (Traefik fronts `frontend`, `cib7`, `mcp`)
  - Keycloak — `kc.example.com` → container `keycloak:8080` (kept
    separate to preserve the issuer URL stamped into existing JWTs;
    front it with its own TLS terminator or extend Traefik to route
    it too)
  - RustFS (object storage) — `s3.example.com` → container `rustfs:9000`,
    fronted by your own TLS terminator. Browsers hit it directly with
    presigned PUT/GET URLs minted by the backend, so it needs its own
    public hostname (`PUBLIC_S3_URL` in `.env`); set `RUSTFS_DOMAIN`
    inside the rustfs container to match so virtual-host signing passes.
- **A clone of this repo on the server.** This document's path builds
  from source (`--build`). If you don't need unpushed changes, skip the
  clone entirely and use the pull-only setup in
  [`deploy/README.md`](../deploy/README.md) instead.
- **Ports 80 and 443 free** on the host — Traefik binds both. :80
  exists only to issue a 308 redirect to :443.

## Files you touch

| File | Purpose |
|---|---|
| `.env` | Per-deploy hostnames + secrets. Copy from `.env.example`. |
| `keycloak/realm-export.json` | **Nothing to edit for a deployment.** Client secrets, redirect URIs, web origins, post-logout URLs and the realm's `frontendUrl` all carry `${...}` placeholders that Keycloak resolves from the environment while importing, so `.env` is the single source. The import runs only on the first boot of a fresh container, so any change here needs Keycloak recreated. ⚠ Developers: `deploy/keycloak/realm-export.json` is a copy for the pull-only bundle — keep the two in sync when changing clients/roles/users. |
| `/opt/volumes/traefik/certs/*.{crt,key}` | TLS certificate + private key. Read by Traefik via the file provider. |
| `/opt/volumes/traefik/dynamic/tls.yml` | Tells Traefik which cert files to load. Copied from `traefik/dynamic/tls.yml.example`. Hot-reloaded (no restart on cert rotation). |
| `/opt/volumes/traefik/dynamic/routes.yml` | Every route, plus the security-header and rate-limit middlewares. Copied from `traefik/dynamic/routes.yml.example`. The prod Traefik uses the file provider only and mounts no Docker socket, so the `traefik.*` labels in `docker-compose.yml` do nothing here. Hot-reloaded. |
| `docker-compose.prod.yml` | Overlay that reads `.env`. Don't normally edit. |
| `docker-compose.yml` | Base file. Don't edit — overrides go in the overlay. |

## Step-by-step

### 1. Edit the realm export

**URLs are automatic.** The three browser-facing clients carry
`${PUBLIC_FRONTEND_URL}` placeholders, and the realm's own `frontendUrl`
attribute carries `${PUBLIC_KEYCLOAK_URL}`; Keycloak resolves both from
the environment while it imports the realm, and the compose files pass
those two variables to the Keycloak container. Nothing to edit for a new
hostname:

```jsonc
// cib7-frontend (public SPA)
"redirectUris": ["http://localhost:3000/*", "http://localhost:5173/*",
                 "${PUBLIC_FRONTEND_URL}/*"]

// cib7-webapps (Cockpit/Tasklist/Admin SSO). Traefik routes /camunda,
// /login and /oauth2 through the SPA host, so these live on the SPA URL,
// not a separate engine hostname. The :8080 pair covers the engine run
// standalone with `mvn spring-boot:run`.
"redirectUris": ["http://localhost:3000/login/oauth2/code/keycloak",
                 "http://localhost:3000/camunda/*",
                 "http://localhost:8080/login/oauth2/code/keycloak",
                 "http://localhost:8080/camunda/*",
                 "${PUBLIC_FRONTEND_URL}/login/oauth2/code/keycloak",
                 "${PUBLIC_FRONTEND_URL}/camunda/*"]

// cib7-mobile (Flutter app, served under /mobile on the SPA host)
"redirectUris": ["http://localhost:3001/*", "http://localhost:3000/mobile/*",
                 "${PUBLIC_FRONTEND_URL}/mobile/*"]
```

**Neither are the secrets.** The three confidential clients carry
placeholders as well, so they come from `.env` alone:

```jsonc
// cib7-webapps  (Cockpit/Tasklist/Admin SSO)
"secret": "${KEYCLOAK_WEBAPPS_CLIENT_SECRET}"

// cib7-backend  (engine's identity-plugin service account)
"secret": "${KEYCLOAK_BACKEND_CLIENT_SECRET}"

// cib7-business (business microservice's /engine-rest service account)
"secret": "${KEYCLOAK_BUSINESS_CLIENT_SECRET}"
```

Generate them with `openssl rand -hex 32`. Hex only: a `+`, `/` or `=`
from base64 inside a Keycloak client secret breaks the form-encoded token
request with `401 invalid_client`. Set them in `.env` (next step) and
nothing else needs touching — when the same value had to be written into
both files, a mismatch took the engine down at startup with
`unauthorized_client / Invalid client credentials`, an error that names
neither file.

Both the placeholders and the secrets are read once, on the first start.
If `PUBLIC_FRONTEND_URL` changes later, the running realm keeps the old
URIs until the realm is re-imported (see
[Day-2 operations](#day-2-operations)) or the clients are edited in the
Keycloak admin console.

Also consider removing or renaming the seeded `bart`/`homer` users —
they exist for demo logins.

### 2. Write `.env`

```bash
cp .env.example .env
# edit with your values
```

Minimum fields: `PUBLIC_KEYCLOAK_URL`, `PUBLIC_FRONTEND_URL`,
`PUBLIC_ENGINE_URL`, `PUBLIC_S3_URL`, `KEYCLOAK_ADMIN_PASSWORD`,
`KEYCLOAK_BACKEND_CLIENT_SECRET`, `KEYCLOAK_WEBAPPS_CLIENT_SECRET`,
`KEYCLOAK_BUSINESS_CLIENT_SECRET`, `RUSTFS_ACCESS_KEY`,
`RUSTFS_SECRET_KEY`, `INTERNAL_TASK_TOKEN`, `BUS_TOKEN`,
`LINK_SIGNING_SECRET`, `PAYMENT_PROVIDER_SECRET`. The overlay has no
defaults for the secrets, so an unset one reaches the service as an empty
string; generate each with `openssl rand -hex 32`. `.env` is git-ignored,
as is every `.env.*` except `.env.example`.

### 3. Prepare `/opt/volumes/traefik/`

Traefik mounts four bind-mount paths from the host. Create them and
seed the dynamic config:

```bash
sudo mkdir -p /opt/volumes/traefik/{certs,dynamic,logs,acme}
sudo chmod 700 /opt/volumes/traefik/certs       # private keys live here
sudo chmod 700 /opt/volumes/traefik/acme        # reserved for future ACME use

# Drop the static-cert dynamic config template into place.
sudo cp traefik/dynamic/tls.yml.example /opt/volumes/traefik/dynamic/tls.yml
sudo $EDITOR /opt/volumes/traefik/dynamic/tls.yml   # set the cert filenames

# Routes + security headers + rate limits. Scope the rules with Host(...)
# if several hostnames point at this box.
sudo cp traefik/dynamic/routes.yml.example /opt/volumes/traefik/dynamic/routes.yml

# Drop your cert + key. Filenames must match what tls.yml points at.
sudo cp /path/to/your-cert.crt /opt/volumes/traefik/certs/app.example.com.crt
sudo cp /path/to/your-key.key  /opt/volumes/traefik/certs/app.example.com.key
sudo chmod 644 /opt/volumes/traefik/certs/*.crt
sudo chmod 600 /opt/volumes/traefik/certs/*.key
```

After the stack is up, **cert rotation is hot-reload**: replace the
files in `/opt/volumes/traefik/certs/` and Traefik picks them up
within a few seconds — no `docker compose restart`. Same for any
changes to `dynamic/tls.yml`.

> The `acme/` directory is reserved. If you later switch from static
> certs to ACME / Let's Encrypt, the acme.json account file lands here
> — no compose changes beyond the `command:` flags in the overlay.

### 4. Build and start

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
    --profile traefik up -d --build
```

`--profile traefik` is required: Traefik is profile-gated in the base
compose file because Docker Desktop on Windows leaves its docker
provider in a retry loop (the dev frontend nginx already covers every
public path, so dev runs without Traefik). In prod Traefik IS the
ingress, so the flag must be passed on every prod `up`. The overlay
replaces the base file's Docker provider with the file provider and drops
the Docker socket mount: a socket inside the internet-facing container would
make a Traefik compromise a root compromise of the host.

First boot takes a few minutes: Maven downloads engine deps, Vite
builds the SPA (with your Keycloak URL baked in), Keycloak imports the
realm, and the engine waits for Keycloak's healthcheck before starting.

Watch progress:

```bash
docker compose logs -f
```

### 5. Verify

| Check | Expected |
|---|---|
| `curl -sI http://app.example.com/` | `308`, `Location: https://app.example.com/` (Traefik :80→:443 redirect) |
| `curl -sI https://app.example.com/` | `200`, with `strict-transport-security`, `content-security-policy` (naming your Keycloak and S3 origins), `x-frame-options: DENY`, `x-content-type-options: nosniff`, `referrer-policy: no-referrer` and `permissions-policy` |
| `curl -s -o /dev/null -w '%{http_code}\n' https://app.example.com/api/internal/documents/index-case` | `404` (engine-only paths are never routed from outside) |
| `for i in $(seq 40); do curl -s -o /dev/null -w '%{http_code} ' https://app.example.com/api/public/vehicle-registry/vehicles; done` | `200`s, then `429`s once the burst of 20 is spent |
| `openssl s_client -connect app.example.com:443 -servername app.example.com </dev/null 2>/dev/null \| openssl x509 -noout -subject -dates` | Your cert's subject + validity window |
| Open `https://app.example.com/` in a browser | Redirects to `https://kc.example.com/realms/cib7-poc/...` |
| Log in with a Keycloak user | Lands on the SPA |
| `curl -s https://app.example.com/engine-rest/process-definition` | `200`, JSON array naming the four process keys (Traefik routes `/engine-rest` to `cib7:8080`). This is the one anonymous engine route — every other `/engine-rest/**` path answers an unauthenticated curl with `401`. |
| `curl -s https://app.example.com/api/public/vehicle-registry/vehicles` | `200`, JSON array of ten vehicles (Traefik routes `/api` to `backend:8085`) |
| Open `https://app.example.com/camunda/app/cockpit/` | Cockpit login page (OAuth2 round-trip through Keycloak) |
| `curl -sI https://kc.example.com/realms/cib7-poc/.well-known/openid-configuration` | `200`, `issuer: https://kc.example.com/realms/cib7-poc` |
| Open MCP from Claude Desktop / Cursor at `https://app.example.com/mcp` | OAuth pop, then `list_services` returns the two services |
| `tail -f /opt/volumes/traefik/logs/access.log` | Lines for each request, with router + service columns |

If the SPA loads but engine calls 401, the most common cause is an
`iss` mismatch — `PUBLIC_KEYCLOAK_URL` in `.env` must equal exactly
what browsers see in the address bar during login (including scheme
and trailing-slash policy).

## TLS termination

**Traefik terminates TLS directly.** No host-level Caddy / nginx is
needed in front for the SPA + engine + MCP hostname — Traefik binds
`:80` (redirect) and `:443` (TLS) on the host. Certs are static
(admin-supplied) and loaded via the file provider from
`/opt/volumes/traefik/dynamic/tls.yml`. Rotate certs by replacing the
files under `/opt/volumes/traefik/certs/` — Traefik hot-reloads them.

The MCP `/mcp` endpoint streams Server-Sent Events. Traefik passes
SSE through cleanly with its defaults; if you ever put another
TLS proxy in front (e.g. a cloud load balancer), set long read
timeouts and disable response buffering on `/mcp` — otherwise
Claude Desktop sees a stalled stream.

### Keycloak's TLS

Keycloak still listens on plain HTTP (`:8180` published to the host)
and is **not** fronted by this stack's Traefik. Either:

- put your own TLS terminator (Caddy, nginx, cloud LB) in front of
  `localhost:8180` for the `kc.example.com` hostname, **or**
- extend this Traefik to route a second hostname to the `keycloak:8080`
  service: uncomment the two `keycloak` routers in `routes.yml`. The
  `keycloak-login` one rate-limits the login and token endpoints per IP
  (docs/security.md rule 8). Traefik already shares the `ingress-idp`
  network with Keycloak, and `KC_PROXY_HEADERS=xforwarded` is already set
  in the prod overlay; drop the `8180:8080` host port mapping.

Keeping Keycloak on its own port is the documented compromise for the
POC — it avoids re-importing the realm just to move the issuer URL.
See the project memory note `keycloak-import-realm-only-once`.

## Day-2 operations

**Restart the stack** (preserves the Keycloak realm export but wipes
engine process state — H2 is in-memory):

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
    --profile traefik restart
```

**Upgrade after a `git pull`** (rebuilds images, picks up new BPMN /
React / MCP code):

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
    --profile traefik up -d --build
```

**View logs for one service:**

```bash
docker compose logs -f cib7        # engine
docker compose logs -f backend     # business microservice (/api, documents, S3)
docker compose logs -f keycloak    # auth
docker compose logs -f mcp         # AI sidecar
docker compose logs -f traefik     # ingress (router + cert decisions)

# Traefik also writes structured access + diagnostic logs to the host:
tail -F /opt/volumes/traefik/logs/access.log
tail -F /opt/volumes/traefik/logs/traefik.log
```

**Centralised logs.** `cib7`, `backend`, `esb`, `mcp` and `pdf-renderer`
all ship structured GELF to the in-stack Graylog, tagged with the service
name, the log level and the Keycloak user id behind the request. Graylog is
published on `127.0.0.1:9900` on the host only, and its GELF inputs are not
published at all — reach the UI over an SSH tunnel, never through Traefik:

```bash
ssh -N -L 9900:127.0.0.1:9900 <user>@<host>
# then open http://localhost:9900 — admin / GRAYLOG_ROOT_PASSWORD
```

Set `GRAYLOG_PASSWORD_SECRET`, `GRAYLOG_ROOT_PASSWORD` and
`GRAYLOG_ROOT_PASSWORD_SHA2` in `.env` before first start (the prod overlay
has no defaults for the first two on purpose). If the UI loads but every
request fails, `GRAYLOG_HTTP_EXTERNAL_URI` does not match the URL in your
address bar. Full detail, including the field contract and troubleshooting,
is in [`logging.md`](logging.md).

**Rotate a TLS certificate.** Replace the files in
`/opt/volumes/traefik/certs/` (keep the filenames pointed at by
`dynamic/tls.yml`, or update `tls.yml` to match). Traefik's file
watcher picks up the change within a few seconds — no `docker compose
restart` needed. Verify the cert in use:

```bash
openssl s_client -connect app.example.com:443 -servername app.example.com </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -dates -fingerprint
```

**Re-import a changed realm export.** Keycloak's `--import-realm` runs
once. To re-import, recreate the container:

```bash
docker compose rm -sf keycloak
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d keycloak
```

**Mailpit inbox.** The base compose keeps Mailpit network-internal —
the UI is only published when the `dev` profile is active
(`docker compose --profile dev up -d mailpit-ui`). For production, omit
the profile and Mailpit stays unreachable from the host. If you need
the inbox visible on a public hostname, add a Traefik label to the
`mailpit-ui` service (or to a new dedicated route) with an `auth`
middleware in front.

## Ingress and container hardening

What the stack does on every deployment, so you know what to check after
changing it (the rules themselves are in [`security.md`](security.md)):

- **Headers.** The frontend and mobile nginx set `Content-Security-Policy`,
  `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer` and `Permissions-Policy` on every response,
  error pages included. The SPA's CSP is generated at container start from
  `KEYCLOAK_URL` and `S3_PUBLIC_URL`, because it must name those two
  origins: if the browser shows a CSP violation for Keycloak or RustFS,
  one of them differs from what the browser actually uses. Proxied JSON
  APIs get `default-src 'none'`; the engine webapps keep their own nonce
  CSP. Traefik adds HSTS and the same headers on every TLS router.
- **Rate limits.** `/api/public/**` is limited to 10 requests/s per client
  IP (burst 20) and `/mcp` to 5/s (burst 20), in nginx when it is the front
  door and in Traefik when Traefik is. Over the limit the answer is `429`.
- **Internal paths.** `/api/internal/**` answers `404` at the edge; only
  the ESB calls it, from inside the `esb-backend` network.
- **Networks.** Each pair of containers that talk shares a network, and no
  other pair does (map in [`architecture.md`](architecture.md#networks)).
  Gotenberg sits on an `internal` network with only `pdf-renderer`, renders
  with JavaScript disabled and with a deny list for internal hosts.
- **Non-root images, pinned versions.** Every image built here runs as a
  non-root user; frontend and mobile listen on 8080 inside the container
  for that reason. Base and third-party images carry exact version tags,
  which Dependabot bumps.

## Production gaps the overlay does not solve

These remain real obstacles between this POC and a system you can
operate in production. They are not "polish" — each one is a decision
you have to make before exposing the stack to real users.

| Gap | What's actually there | What production needs |
|---|---|---|
| **Engine + backend databases** | In-memory H2 in both Java modules — engine process state, history, and deployments wipe on every engine restart, and the backend's `Document` metadata table wipes with the backend. Auto-deploy of BPMN/DMN on startup is what makes the app come back at all. | PostgreSQL with a mounted volume for both modules (TODOS T1). Remove the H2 runtime deps, add `spring.datasource.*` and a `postgres` compose service. Non-trivial — schema migration on every CIB seven upgrade. |
| **Keycloak database** | Built-in dev H2; the realm is re-imported from `realm-export.json` on every container start. User-created accounts (via `send_account_invitation`, self sign-up, password resets) are wiped on every Keycloak restart. | External Postgres for Keycloak as well, `start` (not `start-dev`), and remove `--import-realm` after the first boot. |
| **Mailpit as mail backend** | The BPMN's email service tasks POST to Mailpit's `/api/v1/send` JSON endpoint — a Mailpit-specific wire format, not standard SMTP. | Either keep Mailpit and forward its SMTP relay to a real mail server (cleanest), or rewrite the connector calls in `cib7/src/main/resources/processes/*.bpmn` + `templates/*.ftl` to talk to your mail provider's API. |
| **Webapps client secrets in YAML defaults** | `application.yaml` has `${KEYCLOAK_*_CLIENT_SECRET:cib7-*-secret}` defaults that match the dev realm export. Forgetting to set the env var falls back to the dev secret silently. | Remove the defaults, fail-fast on missing env vars, manage secrets via Docker secrets or your platform's secret store. |
| **No BFF** | The SPA calls `/engine-rest` directly with a Bearer JWT. JWT is validated and authorization runs against `IdentityService`, but every engine REST endpoint is reachable from the browser. | Add a backend-for-frontend that exposes only the calls the SPA needs (the spec calls for this — see [`human-role-react-forms-spec.md`](human-role-react-forms-spec.md)). |
| **Bart/Homer demo users** | Seeded in the realm export with username-equals-password. | Remove or disable them before going live. |
| **Curated vehicle registry** | The "Look up vehicle in registry" service task calls the backend's hard-coded ten-vehicle catalog (`/api/public/vehicle-registry`) — a stand-in for the real Liiklusregister. | Point the backend (or the BPMN connector) at the real registry API, with retry / circuit-breaker handling. |
| **Mock payment provider** | Fees are paid through `MockPaymentProvider` (`backend/.../payment/mockprovider`), a demo bank that signs its callback with `PAYMENT_PROVIDER_SECRET`. The merchant side already verifies a signed callback for the server-computed amount. | Replace only the `mockprovider` package with a client for a real provider (implementing `PaymentProvider`) and point the provider's webhook at `POST /api/public/payments/callback` with its own signing secret. |
| **Document read authorization** | Any authenticated user who knows a process-instance id can list/download its documents — the engine's per-instance permission check was dropped when documents moved to the backend (documented in `DocumentsController`). | Forward the caller's Bearer to `/engine-rest` and require READ_INSTANCE on the case before serving metadata or presigned URLs. |

For the runtime topology these all sit inside, read
[`architecture.md`](architecture.md). For BPMN / engine wiring, see
[`cib7.md`](cib7.md).
