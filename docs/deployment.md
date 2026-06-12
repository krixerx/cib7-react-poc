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
| `keycloak/realm-export.json` | Redirect URIs, web origins, and client secrets. Edit **before first boot** — Keycloak's `--import-realm` runs once (see project memory: realm import is one-shot). ⚠ Developers: `deploy/keycloak/realm-export.json` is a copy for the pull-only bundle — keep the two in sync when changing clients/roles/users. |
| `/opt/volumes/traefik/certs/*.{crt,key}` | TLS certificate + private key. Read by Traefik via the file provider. |
| `/opt/volumes/traefik/dynamic/tls.yml` | Tells Traefik which cert files to load. Copied from `traefik/dynamic/tls.yml.example`. Hot-reloaded (no restart on cert rotation). |
| `docker-compose.prod.yml` | Overlay that reads `.env`. Don't normally edit. |
| `docker-compose.yml` | Base file. Don't edit — overrides go in the overlay. |

## Step-by-step

### 1. Edit the realm export

Before the very first `docker compose up`, open
`keycloak/realm-export.json` and replace localhost URLs and dev
secrets. Three clients need attention:

```jsonc
// cib7-frontend (public SPA)
"redirectUris": ["https://app.example.com/*"],
"webOrigins":   ["https://app.example.com"],
"attributes": {
  "post.logout.redirect.uris": "https://app.example.com/*"
}

// cib7-webapps (Cockpit/Tasklist/Admin SSO)
// NOTE: Traefik now routes /camunda + /login + /oauth2 through the SPA
// host, so the redirect URIs live on app.example.com, not a separate
// engine.example.com.
"secret": "<paste KEYCLOAK_WEBAPPS_CLIENT_SECRET>",
"redirectUris": [
  "https://app.example.com/login/oauth2/code/keycloak",
  "https://app.example.com/camunda/*"
],
"webOrigins": ["https://app.example.com"],
"attributes": {
  "post.logout.redirect.uris": "https://app.example.com/*"
}

// cib7-backend (engine's identity-plugin service account)
"secret": "<paste KEYCLOAK_BACKEND_CLIENT_SECRET>"

// cib7-business (business microservice's /engine-rest service account)
"secret": "<paste KEYCLOAK_BUSINESS_CLIENT_SECRET>"
```

Generate strong secrets — e.g. `openssl rand -base64 36`. Use the
**same values** in `.env` (next step).

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
`RUSTFS_SECRET_KEY`, `INTERNAL_TASK_TOKEN`.

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
ingress, so the flag must be passed on every prod `up`.

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
| `curl -sI https://app.example.com/` | `200`, `server: nginx` (the SPA's image nginx, behind Traefik) |
| `openssl s_client -connect app.example.com:443 -servername app.example.com </dev/null 2>/dev/null \| openssl x509 -noout -subject -dates` | Your cert's subject + validity window |
| Open `https://app.example.com/` in a browser | Redirects to `https://kc.example.com/realms/cib7-poc/...` |
| Log in with a Keycloak user | Lands on the SPA |
| `curl -sI https://app.example.com/engine-rest/engine` | `200`, JSON array (Traefik routes `/engine-rest` to `cib7:8080`) |
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
- extend this Traefik to route a second hostname (`Host(\`kc.example.com\`)`)
  to the `keycloak:8080` service. That requires the realm export to
  carry `KC_PROXY_HEADERS=xforwarded` (already set in the prod
  overlay) and dropping the `8180:8080` host port mapping.

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
| **Public payment confirmation** | `POST /api/public/payments/{piId}/confirm` trusts the opaque process-instance id as the only credential — anyone with the id can mark the fee paid. | Integrate a real PSP callback (signed webhook) or add a payment-side token/session before exposing the pay page publicly. |
| **Document read authorization** | Any authenticated user who knows a process-instance id can list/download its documents — the engine's per-instance permission check was dropped when documents moved to the backend (documented in `DocumentsController`). | Forward the caller's Bearer to `/engine-rest` and require READ_INSTANCE on the case before serving metadata or presigned URLs. |

For the runtime topology these all sit inside, read
[`architecture.md`](architecture.md). For BPMN / engine wiring, see
[`cib7.md`](cib7.md).
