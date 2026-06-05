# Deployment

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

On the target server:

- **Docker** ≥ 24 with the Compose plugin (`docker compose version`
  works — not the standalone `docker-compose`).
- **A TLS terminator** on the host (Caddy, Traefik, nginx, or a cloud
  load balancer). The compose stack itself speaks plain HTTP.
- **Public DNS** for three hostnames (or one host with three paths if
  you front everything through a single reverse proxy):
  - SPA + MCP — `app.example.com` → container `frontend:80`
  - Engine + Cockpit/Tasklist/Admin — `engine.example.com` → container `cib7:8080`
  - Keycloak — `kc.example.com` → container `keycloak:8080`
- **A clone of this repo on the server.** The build runs from source —
  no published images yet (the GitHub Actions workflow publishes images,
  but compose still builds locally by default).
- **Outbound internet** from the engine container (it hits
  `api.restful-api.dev` from the "Get price" service task; without it
  the demo flow fails at step 2).

## Files you touch

| File | Purpose |
|---|---|
| `.env` | Per-deploy hostnames + secrets. Copy from `.env.example`. |
| `keycloak/realm-export.json` | Redirect URIs, web origins, and client secrets. Edit **before first boot** — Keycloak's `--import-realm` runs once (see project memory: realm import is one-shot). |
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
"secret": "<paste KEYCLOAK_WEBAPPS_CLIENT_SECRET>",
"redirectUris": [
  "https://engine.example.com/login/oauth2/code/keycloak",
  "https://engine.example.com/camunda/*"
],
"webOrigins": ["https://engine.example.com"],
"attributes": {
  "post.logout.redirect.uris": "https://engine.example.com/*"
}

// cib7-backend (engine service account)
"secret": "<paste KEYCLOAK_BACKEND_CLIENT_SECRET>"
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
`PUBLIC_ENGINE_URL`, `KEYCLOAK_ADMIN_PASSWORD`,
`KEYCLOAK_BACKEND_CLIENT_SECRET`, `KEYCLOAK_WEBAPPS_CLIENT_SECRET`.

### 3. Build and start

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

First boot takes a few minutes: Maven downloads engine deps, Vite
builds the SPA (with your Keycloak URL baked in), Keycloak imports the
realm, and the engine waits for Keycloak's healthcheck before starting.

Watch progress:

```bash
docker compose logs -f
```

### 4. Verify

| Check | Expected |
|---|---|
| `curl -sI https://app.example.com/` | `200`, `server: nginx` |
| Open `https://app.example.com/` in a browser | Redirects to `https://kc.example.com/realms/cib7-poc/...` |
| Log in with a Keycloak user | Lands on the SPA |
| `curl -sI https://engine.example.com/engine-rest/engine` | `200`, JSON array |
| `curl -sI https://kc.example.com/realms/cib7-poc/.well-known/openid-configuration` | `200`, `issuer: https://kc.example.com/realms/cib7-poc` |
| Open MCP from Claude Desktop / Cursor at `https://app.example.com/mcp` | OAuth pop, then `list_services` returns the two services |

If the SPA loads but engine calls 401, the most common cause is an
`iss` mismatch — `PUBLIC_KEYCLOAK_URL` in `.env` must equal exactly
what browsers see in the address bar during login (including scheme
and trailing-slash policy).

## TLS termination

The compose stack itself serves plain HTTP. Put a TLS terminator in
front of it. A minimal Caddy config (`/etc/caddy/Caddyfile`) covers
all three hostnames in 10 lines:

```caddyfile
app.example.com {
    reverse_proxy localhost:3000
}

engine.example.com {
    reverse_proxy localhost:8080
}

kc.example.com {
    reverse_proxy localhost:8180
}
```

The MCP `/mcp` endpoint streams Server-Sent Events — the nginx config
inside the frontend image already disables buffering and sets
`proxy_read_timeout 1d`. Mirror these settings on your host-level TLS
proxy if it terminates the SSE connection.

## Day-2 operations

**Restart the stack** (preserves the Keycloak realm export but wipes
engine process state — H2 is in-memory):

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart
```

**Upgrade after a `git pull`** (rebuilds images, picks up new BPMN /
React / MCP code):

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

**View logs for one service:**

```bash
docker compose logs -f cib7        # engine
docker compose logs -f keycloak    # auth
docker compose logs -f mcp         # AI sidecar
```

**Re-import a changed realm export.** Keycloak's `--import-realm` runs
once. To re-import, recreate the container:

```bash
docker compose rm -sf keycloak
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d keycloak
```

**Mailpit inbox.** The base compose still publishes Mailpit's UI on
`:8025` for inspection. If you don't want the public host exposing it,
add an override in the prod overlay that removes the `ports` mapping.

## Production gaps the overlay does not solve

These remain real obstacles between this POC and a system you can
operate in production. They are not "polish" — each one is a decision
you have to make before exposing the stack to real users.

| Gap | What's actually there | What production needs |
|---|---|---|
| **Engine database** | In-memory H2 — process state, history, and deployments wipe on every backend restart. Auto-deploy of BPMN/DMN on startup is what makes the app come back at all. | PostgreSQL with a mounted volume. Remove the H2 runtime dep from `cib7/pom.xml`, add `spring.datasource.*` and a `postgres` compose service, drop the `seed-history` workaround. Non-trivial — schema migration on every CIB seven upgrade. |
| **Keycloak database** | Built-in dev H2; the realm is re-imported from `realm-export.json` on every container start. User-created accounts (via `send_account_invitation`, self sign-up, password resets) are wiped on every Keycloak restart. | External Postgres for Keycloak as well, `start` (not `start-dev`), and remove `--import-realm` after the first boot. |
| **Mailpit as mail backend** | The BPMN's email service tasks POST to Mailpit's `/api/v1/send` JSON endpoint — a Mailpit-specific wire format, not standard SMTP. | Either keep Mailpit and forward its SMTP relay to a real mail server (cleanest), or rewrite the connector calls in `cib7/src/main/resources/processes/*.bpmn` + `templates/*.ftl` to talk to your mail provider's API. |
| **Webapps client secrets in YAML defaults** | `application.yaml` has `${KEYCLOAK_*_CLIENT_SECRET:cib7-*-secret}` defaults that match the dev realm export. Forgetting to set the env var falls back to the dev secret silently. | Remove the defaults, fail-fast on missing env vars, manage secrets via Docker secrets or your platform's secret store. |
| **No BFF** | The SPA calls `/engine-rest` directly with a Bearer JWT. JWT is validated and authorization runs against `IdentityService`, but every engine REST endpoint is reachable from the browser. | Add a backend-for-frontend that exposes only the calls the SPA needs (the spec calls for this — see [`human-role-react-forms-spec.md`](human-role-react-forms-spec.md)). |
| **Bart/Homer demo users** | Seeded in the realm export with username-equals-password. | Remove or disable them before going live. |
| **External API dependency** | The "Get price" service task calls `api.restful-api.dev` from the engine. No retry / circuit breaker. | Replace with your actual catalogue source, or front it with a service that handles outages. |
| **Public Mailpit UI** | Port 8025 is published from `mailpit` in the base compose. | Remove the `ports:` mapping in the prod overlay, or put it behind an auth gateway. |

For the runtime topology these all sit inside, read
[`architecture.md`](architecture.md). For BPMN / engine wiring, see
[`cib7.md`](cib7.md).
