# Architecture

**When to read this:** before changing anything that crosses the
React ↔ engine ↔ external-API boundary; when adding a new component, port,
or deployment unit; when debugging "who calls whom" questions.

**Contents**
1. [System overview](#system-overview)
2. [Components](#components)
3. [Request flow — happy path](#request-flow--happy-path)
4. [Ports, URLs, and proxying](#ports-urls-and-proxying)
5. [Deployment topology](#deployment-topology)
6. [Data persistence](#data-persistence)
7. [Security posture (POC)](#security-posture-poc)
8. [Known trade-offs vs. the spec](#known-trade-offs-vs-the-spec)

---

## System overview

**Repo shape — monorepo.** CIB seven engine module (`cib7/`), the business
microservice (`backend/`), frontend (`frontend/`), the Keycloak realm export
(`keycloak/`), Docker orchestration (`docker-compose.yml`), and the docs
(`docs/`) all live in one git repository. They are versioned, built, and
released together — one branch / one PR can change both sides of the React ↔
engine boundary atomically, which is the whole point.

**Module split.** `cib7/` is a *clean engine*: CIB seven 2.2 + plugins +
connectors + BPMN/DMN/FreeMarker resources, no business endpoints. Every
`/api/**` surface (public owner-confirmation / founder-signature / payment
links, the registry module, document storage) lives in `backend/`,
a separate Spring Boot 4 microservice that reaches the engine exclusively
over `/engine-rest` using the `cib7-business` Keycloak service account.

```
  Browser (React SPA) ──OIDC login──▶ Keycloak
   │  (PKCE)
   │  Bearer JWT
   ├─ /engine-rest ───▶  CIB seven 2.2 engine + REST API     (cib7/, Postgres)
   │                     │
   │                     │  http-connector → ${busBaseUrl}
   │                     ▼
   │                  esb — integration bus (Apache Camel, YAML routes)
   │                     │  routes each path to the real downstream system:
   │                     ├──▶ Mailpit /api/v1/send       (email + attachments)
   │                     ├──▶ pdf-renderer /render ──▶ Gotenberg (Chromium → PDF)
   │                     └──▶ backend /api/...           (vehicle lookup, document
   │                                                      move-pending/server-upload,
   │                                                      case-card index-case;
   │                                                      bus injects X-Internal-Token)
   │
   └─ /api/... ───────▶  backend — business microservice  (backend/, Spring Boot 4)
                          │  public confirmations / payments / vehicle registry,
                          │  documents (JPA metadata + S3 presigned URLs),
                          │  case cards (JPA; BPMN milestone tasks POST prose
                          │  status summaries, MCP search_cases reads them)
                          ├──▶ /engine-rest  (cib7-business service account)
                          └──▶ RustFS (S3)   ◀── browser presigned PUT/GET

                       mcp sidecar ◀── Claude Desktop / Cursor / Codex
                       (Bearer-proxy → /engine-rest, documents → backend /api)

  cib7 · backend · esb · mcp · pdf-renderer ──GELF 12201──▶ graylog
                                                            (loopback-only UI,
                                                             SSH tunnel to reach it)
```

The runtime pieces:

- **Keycloak** — OAuth2 / OIDC identity provider. Hosts the login form, issues
  JWT access tokens to the SPA, and exposes its Admin REST API to the backend's
  identity provider plugin.
- **React SPA** — single-page app, talks to Keycloak directly for login (via
  `keycloak-js`, PKCE), then to the same-origin paths `/engine-rest/...`
  (Bearer JWT on every call) and `/api/...` (backend — Bearer for documents,
  public for token-link pages and the vehicle dropdown).
- **Engine service (`cib7/`)** — Spring Boot 3.5 app embedding the CIB seven
  2.2 process engine, exposing `/engine-rest` and the legacy `/camunda`
  webapps. Auto-deploys every BPMN/DMN on the classpath at startup. Validates
  JWTs as an OAuth2 resource server and bridges the authenticated user into
  the engine's `IdentityService` per request. Contains no business REST
  endpoints — only engine, plugins, connectors, and process resources.
- **Business microservice (`backend/`)** — Spring Boot 4 app owning every
  `/api/**` surface: the public token-link endpoints
  (`/api/public/consent/<purpose>` for co-owner confirmation and co-founder
  signature, configured per purpose by the pack's `consent/<purpose>.yaml`,
  `/api/public/payments`), the registry module
  (`/api/{public,internal}/registry/<entity>`, which serves the service
  pack's declared registries such as the `vehicles` Liiklusregister stand-in
  the engine calls via http-connector), `/api/documents` (S3 presigned upload /
  download against RustFS; metadata as a JPA `Document` entity in its own
  Postgres database), and the engine-only `/api/internal/**` endpoints (document
  filing, case index). Talks to the engine only over `/engine-rest`, authenticated
  with the `cib7-business` Keycloak service account (client_credentials; the
  service account sits in `/cib7-admin` so engine authorization passes).
- **RustFS** — S3-compatible object storage for applicant uploads and
  engine-generated PDFs. The backend mints presigned PUT/GET URLs so the
  browser moves bytes directly; port `9000` stays host-published because S3
  signature v4 hashes the host header.
- **Mailpit** — local SMTP+HTTP test server. Notifications (reminder,
  send-back, approval) reach its `/api/v1/send` endpoint through the
  integration bus; the inbox at `:8025` renders them with attachments.
- **Integration bus (`esb`)** — Apache Camel JBang running declarative YAML
  routes (`esb/routes/*.yaml`, no Java). The engine makes every outbound HTTP
  call to a single address (`${busBaseUrl}` = `http://esb:8080`) and the bus
  routes each path to the real downstream system — `/api/v1/send`→Mailpit,
  `/render`→pdf-renderer, `/api/public/**` and `/api/internal/**`→backend
  (injecting `X-Internal-Token` on the internal calls). Every route first
  checks the engine's `X-Bus-Token` (`esb/routes/bus-auth.yaml`). Demonstrates the
  RFP's "all integration crosses the Central Integration Platform" mandate as
  a mediated bus instead of point-to-point connector calls.
- **PDF stack** — two collaborating sidecars: **Gotenberg** (headless
  Chromium) handles the actual rendering, and **pdf-renderer** (a 20-line
  Node sidecar) sits in front of it to give the engine a JSON-in / JSON-out
  REST API. Without pdf-renderer the http-connector would have to build
  multipart bodies and handle binary responses, which would force a custom
  Java delegate.
- **MCP sidecar** — a standalone Node + TypeScript microservice that
  exposes the deployment as an AI-callable surface via the
  [Model Context Protocol](https://modelcontextprotocol.io). Claude
  Desktop (or any MCP-capable client) connects via `/mcp`, completes
  OAuth2 PKCE-loopback against Keycloak, and drives the deployment through
  **eleven tools**: eight process tools (`list_services`, `describe_service`,
  `start_process`, `list_my_tasks`, `get_form_schema`, `complete_task`,
  `list_my_processes`, `query_user_history`) plus three identity tools
  (`get_signup_url`, `get_password_reset_url`, `send_account_invitation`)
  for onboarding and password reset without ever asking the LLM to handle
  credentials. The sidecar is a **Bearer-proxy** in front of `/engine-rest`
  — it forwards the caller's token unchanged on every process call. It
  verifies the JWT signature against Keycloak's JWKS at the door so stale
  tokens surface as HTTP 401 (the signal mcp-remote needs to re-run OAuth);
  it never holds refresh tokens server-side and never persists per-user
  state. The engine remains the authoritative security boundary.
  Per-service variable schemas come from
  `packs/reference/docs/business/services/<svc>/build/mcp-service.json` generated by
  `/service-builder`. Full module guide: [`mcp.md`](mcp.md).

## Components

| Component | Tech | Where | Purpose |
|---|---|---|---|
| React SPA | React 18 + TypeScript + Vite + React Router 6 | `frontend/` | Services / Tasks / TaskDetail pages, hand-written forms |
| SPA auth client | `keycloak-js` | `frontend/src/auth/` | OIDC PKCE login + token refresh; gates every route |
| Ingress (prod compose) | Traefik v3.4 | `docker-compose.yml` (`traefik` service) | Single public front door on `:3000`; path-routes `/engine-rest`, `/camunda`, `/oauth2`, `/login`, `/logout` → cib7; `/api` → backend; `/mcp`, `/.well-known/oauth-protected-resource` → mcp; everything else → frontend. The engine, backend, MCP, frontend, and Mailpit are network-internal — only Traefik, Keycloak, and RustFS publish host ports. |
| HTTP server (prod) | nginx | `frontend/nginx.conf` | Serves built SPA. Cross-service routing has moved to Traefik; this nginx only does the SPA fallback (`try_files $uri /index.html`). |
| Dev server | Vite | `frontend/vite.config.ts` | Serves SPA in dev, proxies `/engine-rest` to `localhost:8080`. (Vite-dev does not use Traefik; the engine still binds 8080 when run via `mvn spring-boot:run`.) |
| Engine app | Spring Boot 3.5, CIB seven 2.2 starter | `cib7/` | Embedded engine + REST API; no business endpoints |
| Business microservice | Spring Boot 4 (webmvc + data-jpa + security) | `backend/` | All `/api/**`: public confirmation/payment links, vehicle registry, documents (JPA `Document` metadata + S3 presigner), case cards (JPA `CaseCard`, one prose status summary per case re-posted by BPMN "Index case" milestone tasks; keyword/filter retrieval for the `search_cases` MCP tool — the AI client does the semantic matching, no embeddings); engine access via `/engine-rest` with the `cib7-business` service account |
| Object storage | RustFS (S3-compatible) | compose service | Applicant uploads + generated PDFs under `process/{piId}/…`; presigned URLs minted by the backend |
| Process engine | CIB seven 2.2 (Camunda 7 fork) | starter dep | Executes BPMN, exposes `/engine-rest` |
| Connect plugin | `cibseven-engine-plugin-connect` | wired in `ConnectorConfiguration.java` | Enables `<camunda:connector>` service tasks |
| Connector | `cibseven-connect-http-client` (official `http-connector`) | declared in `cib7/pom.xml` | HTTP request via Apache HttpClient 5; response body parsed inline with Spin. Every call now targets `${busBaseUrl}` (the integration bus), not a downstream system directly |
| Integration bus | Apache Camel JBang (`apache/camel-jbang`) | `esb/` (declarative YAML routes) | Mediates every engine→downstream HTTP call; engine talks only to `${busBaseUrl}` and the bus routes each path to mailpit / pdf-renderer / backend (injecting `X-Internal-Token` on `/api/documents`) |
| Identity provider plugin | `cibseven-keycloak` 2.1.0 | wired in `com/poc/cib7/keycloak/KeycloakIdentityProvider.java` | `ReadOnlyIdentityProvider`: engine reads users/groups from Keycloak |
| REST API security | Spring Security OAuth2 Resource Server | `com/poc/cib7/keycloak/RestApiSecurityConfig.java` (verbatim from plugin's `sso-kubernetes` example) | Validates Bearer JWTs and pushes user into `IdentityService` per request |
| Engine authorization bootstrap | `com/poc/cib7/AuthorizationBootstrap.java` | local | The only group-level engine grants: applicants list and start services, civil servants read every case and retry jobs. Per-case access comes from `authorization/InitiatorAuthorizationListener.java` and the engine's default task authorizations (admins are handled by the plugin's `administratorGroupName`) |
| Identity provider | Keycloak 26 | `keycloak/cib7-poc-realm.json` + compose service | OIDC; pre-seeded realm `cib7-poc` with two users: `bart` / `bart` (applicant — PartA) and `homer` / `homer` (civil servant + admin — PartB) |
| Database | PostgreSQL 17 | `postgres` compose service, `postgres` Spring profile, Flyway | Engine (database `cib7`) and backend (database `backend`) state on the `postgres-data` volume; in-memory H2 in tests |
| Email sink | Mailpit | compose service | Captures every notification + attachment the process sends; UI at `:8025` |
| PDF generator | Gotenberg 8 (headless Chromium) | compose service | Internal only — converts HTML → PDF over multipart REST |
| PDF adapter | `pdf-renderer/` (Node + Express, 20 LOC) | local module, compose service | JSON-in / JSON-out facade over Gotenberg so the http-connector stays plain HTTP + JSON |
| MCP sidecar | `mcp/` (Node + TypeScript + Express + `@modelcontextprotocol/sdk` + `jose`) | local module, compose service | Streamable HTTP MCP transport at `/mcp`; OAuth2 PKCE-loopback against Keycloak; JOSE jwtVerify at the door; Bearer-forwards to `/engine-rest`; Ajv-validates inputs against per-service manifests; uses `cib7-backend` service account for invitation emails |
| Per-service MCP manifests | `packs/reference/docs/business/services/<svc>/build/mcp-service.json` (generated) | `/service-builder` skill | Variable schemas + audience metadata; loaded by the MCP sidecar at startup |
| Aggregated MCP index | `packs/reference/docs/business/services/build/services.json` (generated) | `/service-builder` skill | Top-level catalog of MCP-callable services |
| Centralised logging | Graylog 7.1 + OpenSearch 2.19 + MongoDB 7 | `docker-compose.yml` (`graylog`, `opensearch`, `graylog-mongodb`, `graylog-init`) | GELF sink for every service we write. Each message carries `service`, `level` + `level_name`, and `user_id` where a Keycloak user is attributable. Web UI bound to `127.0.0.1:9900` only (SSH tunnel); GELF inputs unpublished. Inputs are provisioned by the one-shot `graylog-init` curl sidecar. See [`logging.md`](logging.md) |
| Log shippers | `logback-gelf` (cib7, backend) · Log4j2 `GelfLayout` (esb) · hand-rolled GELF UDP module (mcp, pdf-renderer) | `*/logback-spring.xml`, `esb/log4j2-graylog.xml`, `mcp/src/logging/gelf.ts`, `pdf-renderer/gelf.js` | Three mechanisms because the three runtimes differ; one field contract. JVM services carry `user_id` from the SLF4J MDC, populated by `MdcUserFilter` after Spring Security's chain |
| Container orchestration | Docker Compose | `docker-compose.yml` | Application services: `traefik`, `keycloak`, `cib7`, `backend`, `frontend`, `mobile`, `mailpit` (+ `mailpit-ui` in the `dev` profile), `gotenberg`, `pdf-renderer`, `rustfs`, `mcp`, `esb`. Observability: `graylog`, `opensearch`, `graylog-mongodb`, `graylog-init` |

Detailed file-level wiring lives in [`frontend.md`](frontend.md) and
[`cib7.md`](cib7.md).

## Request flow — happy path

A single "Vehicle Registration" process instance:

```
1. User opens Services page
     SPA → GET /engine-rest/process-definition?latestVersion=true
2. User picks "Vehicle Registration", clicks Start
     SPA → POST /engine-rest/process-definition/key/vehicleRegistration/start
     SPA → GET  /engine-rest/task?processInstanceId={id}    (find first task)
     SPA navigates to /tasks/{taskId}
3. TaskDetail loads the task + variables, resolves the form
     SPA → GET /engine-rest/task/{taskId}
     SPA → GET /engine-rest/task/{taskId}/form-variables
     SPA → looks up formKey "react:owner-vehicle" → schema renderer
     SPA → GET /pack/forms/owner-vehicle.json   (the pack's form definition)
4. The form fetches the vehicle dropdown (browser → backend)
     SPA → GET /api/public/registry/vehicles
   and stages the ID upload (browser → backend → RustFS)
     SPA → POST /api/documents/upload-url → presigned PUT direct to RustFS
5. User submits → SPA completes the task with typed variables
     SPA → POST /engine-rest/task/{taskId}/complete
     ↓
6. Engine promotes the staged upload + looks the vehicle up (job executor)
     Engine → POST {busBaseUrl}/api/internal/documents/move-pending   (bus injects X-Internal-Token)
     Engine → GET  {busBaseUrl}/api/public/registry/vehicles/{vin}
     Spin reads value/age inline; engine writes `price`, `vehicleAgeYears`
     ↓
7. DMN auto-approval policy decides: auto-approve, or create the
   "Transport Authority review" user task (candidateGroup civil-servant)
     Reviewer approves → {decision: "approve"} — or sends back to step 3
     ↓
8. Engine generates + stores the state-fee invoice PDF (both via the bus)
     Engine → {busBaseUrl}/render (→ pdf-renderer) → {busBaseUrl}/api/internal/documents/server-upload (→ backend)
   then parks on "Wait for state fee payment" (receive task)
     Payer → public /pay/{token} page → POST /api/public/payments/{token}/checkout
           → payment provider (demo: MockPaymentProvider, /mock-bank/{sessionId})
     Provider → signed POST /api/public/payments/callback (HMAC, amount checked)
     Backend correlates PaymentReceived via /engine-rest/message
     ↓
9. Engine generates the registration certificate, process ends (Approved)
```

The full REST surface used by the SPA is listed in
[`frontend.md`](frontend.md#camunda-rest-endpoints-used).

## Ports, URLs, and proxying

| Environment | SPA origin | Engine + backend | Keycloak | How `/engine-rest` reaches the engine |
|---|---|---|---|---|
| Docker (`docker compose up`) | `http://localhost:3000` (the frontend nginx, container port 8080; Traefik with `--profile traefik`) | network-internal (nginx, or Traefik, routes `/engine-rest`, `/camunda`, `/oauth2`, `/login`, `/logout` to `cib7:8080` and `/api` to `backend:8085`, except `/api/internal/**`, which answers 404) | `http://localhost:8180` (exposed) | `location /engine-rest/` in `frontend/nginx.conf`; with Traefik, the `cib7-rest` router label on the `cib7` service |
| Local dev | `http://localhost:5173` (Vite) | `http://localhost:8080` (`mvn spring-boot:run`) | `http://localhost:8180` (run Keycloak separately or via `docker compose up keycloak`) | Vite `server.proxy['/engine-rest']` → `http://localhost:8080` |

The SPA **always uses the same-origin paths** `/engine-rest/...` and
`/api/...`. That removes CORS from all engine and backend traffic — no
`Access-Control-*` config on either Java service. (One browser call still
crosses origins: document uploads/downloads go directly to RustFS on
`localhost:9000` with presigned URLs; the backend's `BucketBootstrap` sets
the bucket's CORS policy to the SPA origin for exactly that.)

### Deliberately not published

| Surface | Why | How to reach it |
|---|---|---|
| Graylog web UI | central log store; holds user ids and message content | published on `127.0.0.1:9900` only — `ssh -N -L 9900:127.0.0.1:9900 <user>@<host>` |
| Graylog GELF inputs (`12201` UDP + TCP) | shippers are sibling containers | docker networks only (Graylog joins each shipper's network, see [Networks](#networks)), no host port |
| Backend `/api/internal/**` | engine-only endpoints, called by the ESB with `X-Internal-Token` | `esb-backend` network only; nginx and Traefik answer 404 |
| ESB, Gotenberg, pdf-renderer, Mailpit API | integration plumbing | only from the one service that calls each, see [Networks](#networks) |
| Mailpit web UI | unauthenticated inbox | opt-in `dev` compose profile |
| Mailpit at `/mailpit` (deploy bundle, `tls` profile) | shared demo inbox: any logged-in user reads every mail | `mailpit-auth` (oauth2-proxy, Keycloak client `cib7-mailpit`); `DELETE` and the send API unrouted; a demo exemption in `docs/security.md` |
| Traefik dashboard | auth-free | `127.0.0.1:8081` only |

## Deployment topology

`docker-compose.yml` defines the application services below, plus the
`dev`-profile `mailpit-ui` sidecar and the four-container Graylog group:

- **keycloak** — `quay.io/keycloak/keycloak:26.1.5` in `start-dev --import-realm`
  mode. Mounts `keycloak/cib7-poc-realm.json` so the realm boots pre-seeded
  (realm + clients + role + group + user). Publishes port `8180` mapped to
  container port `8080`. `KC_HOSTNAME_URL=http://localhost:8180` pins a
  single canonical issuer URL.
- **cib7** — the reference pack's engine layer
  (`packs/reference/docker/engine.Dockerfile`) on the core image, which
  compose builds from `cib7/Dockerfile` as `cib7-core` (`scale: 0`); the
  same split holds for **backend** (`backend-core`) and **mcp** (`mcp-core`).
  CI publishes the core images as `cib7-poc-<name>-core` and the reference
  pack's layers under the names the deploy bundle pulls.
  Network-internal on port `8080` (reached through Traefik / the frontend
  nginx). Reaches Keycloak over the docker network at `http://keycloak:8080`
  (internal), while the browser uses `http://localhost:8180` (external) —
  see the "issuer-URL split" note below. Depends on `keycloak` (healthy),
  `mailpit` (started), `pdf-renderer` (started), and `esb` (started); reads
  `BUS_URL=http://esb:8080` (exposed to BPMN as `${busBaseUrl}`). Every
  outbound HTTP call — email, PDF, and the document/vehicle-registry tasks —
  goes to the integration bus, which routes each path to the real downstream
  system. The engine no longer carries `MAIL_API_URL` / `PDF_API_URL` /
  `BACKEND_API_URL` / `INTERNAL_TASK_TOKEN`, and no longer knows any
  individual system's address.
- **backend** — core image from `backend/Dockerfile`, pack layer
  `packs/reference/docker/backend.Dockerfile`. Network-internal on port
  `8085`; Traefik (and the frontend nginx fallback) route `/api` to it.
  Owns the `/api/**` business surface; depends on `keycloak` (healthy),
  `rustfs` (healthy), and `cib7` (started). Authenticates its
  `/engine-rest` calls with the `cib7-business` service account and shares
  `INTERNAL_TASK_TOKEN` with the integration bus (`esb`), which injects the
  `X-Internal-Token` header on the BPMN-called `/api/internal/**` endpoints
  — the engine never holds that secret.
- **rustfs** — S3-compatible object storage, host-published on `:9000` (the
  browser hits it directly with presigned URLs; S3 signature v4 hashes the
  host header, so it stays off the proxy). Bucket, CORS, and a 24h
  `pending/` lifecycle rule are bootstrapped by the backend on startup.
- **frontend** — the reference pack's portal image: a thin layer
  (`packs/reference/docker/frontend.Dockerfile`) adding the pack's
  `frontend/` and `branding/` under `/pack/` to the core image, which
  compose builds from `frontend/Dockerfile` as `frontend-core` (multi-stage:
  Vite build → `nginx-unprivileged`, uid 101; `scale: 0`, so it never runs on
  its own). CI publishes both: `cib7-poc-frontend-core` and, from it,
  `cib7-poc-frontend`. Publishes port `3000` mapped to container
  port `8080`. Sets the security headers and the `/api/public` and `/mcp`
  rate limits; its CSP is generated at start from `KEYCLOAK_URL` and
  `S3_PUBLIC_URL`. `depends_on: cib7` (start ordering only — nginx does not
  wait for the engine to be healthy). **mobile** is the same shape on
  `3001` → `8080`.
- **mailpit** — `axllent/mailpit:v1.31.3`. Network-internal: web UI and send
  API on `8025`, SMTP on `1025` (unused — the engine uses the HTTP API via
  the bus). The UI is published on `:8025` only through the `dev`-profile
  `mailpit-ui` socat sidecar.
- **gotenberg** — `gotenberg/gotenberg:8.37.0`. Headless Chromium wrapped in
  a REST API. Internal only (no host port mapping) on an `internal` network
  shared with pdf-renderer alone; renders with JavaScript disabled and a
  `--chromium-deny-list` covering `file://` outside its work dir, private
  and loopback IPs and every single-label (compose service) hostname.
- **pdf-renderer** — built from `pdf-renderer/Dockerfile` (Node 24 +
  Express). Internal only on port 8088. JSON-in / JSON-out adapter in
  front of Gotenberg. Hides Gotenberg's multipart input format and binary
  output from the http-connector, which only handles plain
  `application/json` cleanly.
- **esb** — built from `esb/Dockerfile` (`apache/camel-jbang`). Internal only
  on port `8080`. The integration bus: the engine POSTs every outbound call to
  `http://esb:8080` and the declarative YAML routes (`esb/routes/*.yaml`,
  loaded via `camel run --source-dir`) forward each path to mailpit /
  pdf-renderer / backend. Accepts a call only with `X-Bus-Token` equal to its
  `BUS_TOKEN` (the engine sends it; the bus strips it before forwarding).
  Holds `INTERNAL_TASK_TOKEN` and injects `X-Internal-Token` on the
  `/api/internal` route. Not in `esb.depends_on`'s
  `backend` (that would be a `backend → cib7 → esb` startup cycle); the route
  resolves the backend at request time.
- **mcp** — core image from `mcp/Dockerfile` (repo root as context, for
  the core's `core-v1.json`), pack layer `packs/reference/docker/mcp.Dockerfile`
  (the pack's specs folder and engine form schemas). Node 24 +
  TypeScript + Express + `@modelcontextprotocol/sdk` + `jose`. Internal
  only on port 8090; exposed publicly via nginx at `/mcp` and the
  OAuth resource metadata at `/.well-known/oauth-protected-resource`.
  Depends on `keycloak` (healthy) and `cib7` (started). Env-driven
  (`MCP_RESOURCE_URL`, `MCP_APPLICANT_PORTAL_URL`, `MCP_MAILPIT_URL`,
  `KEYCLOAK_ISSUER_URL`, `KEYCLOAK_INTERNAL_URL`, `KEYCLOAK_REALM`,
  `KEYCLOAK_ADMIN_CLIENT_ID`, `KEYCLOAK_ADMIN_CLIENT_SECRET`,
  `ENGINE_URL`, `SERVICES_SPEC_DIR`) so the same image works in dev and
  CI without
  rebuilds.
- **graylog / opensearch / graylog-mongodb / graylog-init** — the centralised
  log store. `graylog` publishes its web UI on `127.0.0.1:9900` only
  (Graylog's native 9000 is taken by RustFS on the host) and keeps its GELF
  UDP + TCP inputs on port 12201 **unpublished**, because every shipper is a
  sibling container. OpenSearch is wired directly via
  `GRAYLOG_ELASTICSEARCH_HOSTS` with `GRAYLOG_SKIP_PREFLIGHT_CHECKS=true`, so
  no Data Node and no interactive preflight UI stands between
  `docker compose up` and a working stack. `graylog-init` is a one-shot
  `curlimages/curl` sidecar that creates the two inputs through the REST API —
  Graylog keeps inputs in MongoDB, so a fresh volume would otherwise have
  nothing listening. Unlike every other service here these four use **named
  volumes** (`graylog-data`, `graylog-opensearch-data`,
  `graylog-mongodb-data`, `graylog-mongodb-config`): OpenSearch and MongoDB run
  non-root and are strict about data-directory ownership, which host bind
  mounts get wrong on Windows and macOS. Details in [`logging.md`](logging.md).

### Networks

Containers share a network only when one of them calls the other
(`docs/security.md` rule 9). All three compose files use the same map; the
networks have explicit names (`cib7-poc-<name>`), so
`docker network inspect cib7-poc-bus` lists who is on one.

| Network | Members | Carries |
|---|---|---|
| `edge` | traefik, frontend, mobile, cib7, backend, mcp | ingress (Traefik or the frontend/mobile nginx) to the services it routes |
| `app` | cib7, backend, mcp, keycloak | backend → cib7, mcp → cib7 / backend, all three → `keycloak:8080` (JWKS, tokens, admin API) |
| `storage` | backend, rustfs | the backend's S3 client |
| `bus` | cib7, esb, graylog | the engine's only outbound channel; GELF from cib7 and esb |
| `esb-backend` | esb, backend, graylog | the bus calling `/api/public` and `/api/internal`; the backend's GELF |
| `mail` | esb, mailpit, mailpit-ui | outbound email |
| `pdf` | esb, pdf-renderer, graylog | PDF rendering requests; pdf-renderer's GELF |
| `render` (internal) | pdf-renderer, gotenberg | HTML → PDF; no route off the host |
| `gelf-mcp` | mcp, graylog | the MCP sidecar's GELF |
| `logstore` (internal) | graylog, opensearch, graylog-mongodb, graylog-init | Graylog's own stores and its input provisioning |
| `ingress-idp`, `ingress-s3` (prod overlay, `deploy/`) | traefik + keycloak / rustfs | only used when `routes.yml` routes the Keycloak or S3 hostname |

Graylog joins its shippers' networks rather than every shipper joining one
log network, because a shared log network would connect every shipper to
every other one, the ESB included. Two consequences to keep in mind:
adding a call between two services means putting both on a common network
here, and the eleven-or-so bridge networks take a visible share of Docker's
default address pool (`could not find an available, non-overlapping IPv4
address pool` means prune unused networks or widen `default-address-pools`).

Keycloak stays reachable by its compose name on `app` for every service that
validates tokens. No container needs the public issuer URL: the `iss` claim is
a string compare against `KEYCLOAK_ISSUER_URL` (see the issuer-URL split
below).

The engine and the backend keep their state in one Postgres container, in
separate databases with separate login roles, on the `postgres-data` volume
(see Data persistence below). Keycloak uses its built-in dev H2; RustFS
bind-mounts `./.data/rustfs` in dev so uploaded bytes survive.

**Issuer-URL split.** Keycloak issues JWTs with an `iss` claim matching its
`KC_HOSTNAME_URL`, pinned here to `http://localhost:8180` — the URL the
browser uses. The backend, sitting in a docker container, can't reach
`localhost:8180` (that resolves to the backend itself). The standard
production pattern is to use two URLs:

| Role | URL | Used by |
|---|---|---|
| Public (`iss` claim, browser login redirects) | `http://localhost:8180` | Browser (`keycloak-js`), the engine's and backend's `iss` validators |
| Internal (server-to-server) | `http://keycloak:8080` | Engine's identity provider plugin (Admin REST), JWKS fetches and the backend's token endpoint |

The engine's `RestApiSecurityConfig.jwtDecoder()` builds a `NimbusJwtDecoder`
with `.withJwkSetUri(jwkSetUri)` (internal URL) and validates the `iss` claim
against the public URL with `JwtValidators.createDefaultWithIssuer` (a plain
string compare — no HTTP call). The backend does the same split purely via
Spring Boot properties (`spring.security.oauth2.resourceserver.jwt.*`). The
plugin's Admin REST calls also use the internal URL. Tokens minted by the
same Keycloak carry `iss=localhost:8180` regardless of which interface a
service talks to. If you change the Keycloak hostname for production, change
`KC_HOSTNAME_URL` (Keycloak), `KEYCLOAK_ISSUER_URL` (engine + backend, public
URL), `KEYCLOAK_URL` (engine + backend, internal URL), and
`frontend/src/auth/keycloak.ts` together.

## Data persistence

- **One Postgres container, two databases.** `postgres` (17, alpine) holds
  `cib7` for the engine and `backend` for the business service, each owned by
  its own login role that cannot connect to the other database.
  `postgres/init/01-databases.sh` creates them on the first start of an empty
  `postgres-data` volume; the passwords come from `.env`
  (`ENGINE_DB_PASSWORD`, `BACKEND_DB_PASSWORD`, `POSTGRES_PASSWORD`). The
  container sits only on the internal `db` network, shared with `cib7` and
  `backend`.
- **Spring profile `postgres`** (set by docker-compose next to `graylog`)
  switches each service from Boot's in-memory H2 to its database. Without it,
  as in tests and `mvn spring-boot:run`, both modules run in-memory H2 through
  the same migrations.
- **Flyway owns both schemas.** The engine's `ACT_*` tables come from
  `com.poc.cib7.db.V1__CibSevenSchema`, which runs the create scripts the CIB
  seven jar ships for the detected database; `camunda.bpm.database.schema-update`
  is `false`, so the engine only checks the version. A CIB seven upgrade adds a
  migration that runs the jar's `db/upgrade` script. The backend's tables come
  from `backend/src/main/resources/db/migration/V*__*.sql`, and Hibernate only
  validates them (`ddl-auto: validate`).
- **What survives.** Process instances, tasks, history, deployments, document
  metadata, payment sessions and case cards survive restarts and image
  updates. Deployments are re-checked at startup by duplicate filtering, so an
  unchanged service is not re-versioned. `docker compose down -v` wipes
  everything. Backups: see `docs/deployment.md`, Day-2 operations.

## Registry module

Registries a service needs (reference data, lookups the engine makes) are
declared in the service pack, not written as Java. The spec's
`packs/reference/docs/business/services/<service>/data/<entity>.md` becomes, through the
service builder, a descriptor `packs/reference/backend/registry/<entity>.yaml`
and a Flyway migration `packs/reference/backend/db/registry/V<n>__*.sql`
(table plus seed rows). The pack's backend layer
(`packs/reference/docker/backend.Dockerfile`) puts them in `/opt/services`
on the classpath (`PropertiesLauncher`, `loader.path`), like the engine's pack.

- `RegistryMigrations` runs the pack's migrations in their own `registry`
  schema with their own Flyway history table, apart from the backend's core
  tables.
- `RegistryCatalog` loads the descriptors and refuses to start on anything
  outside the closed set: names that are not plain identifiers, a table not
  starting with `reg_`, types other than string / integer / number /
  boolean, operations other than list / lookup, endpoint classes other than
  `public` / `internal`, derived fields other than `yearsSince`, or a column
  the database does not have.
- `RegistryController` serves every entity at
  `/api/public/registry/<entity>[/<key>]` or
  `/api/internal/registry/<entity>[/<key>]`, whichever class the descriptor
  names per operation; the other class answers 404, like an unknown entity.
  Identifiers come from the checked descriptor and are quoted, the key is a
  bound parameter, a list returns at most 1,000 rows.

## Co-signing (consent)

Parties other than the applicant (co-owners, co-founders) confirm or sign
through capability links in their email. The engine side was already
generic: `ConsentPartiesListener` takes the variable names from the BPMN and
`CapabilityLinks` mints the tokens. The backend side is now generic too. The
spec's `packs/reference/docs/business/services/<service>/consent.md` becomes, through the
service builder, `packs/reference/backend/consent/<purpose>.yaml`;
`ConsentCatalog` loads it (closed set: variable and message names, wording,
details of type `string`, `number` or `names`, documents by id variable and
category), and `ConsentController` serves every purpose at
`/api/public/consent/<purpose>/<token>` (`/status`, sign, `/send`,
`/documents/<name>/download-url`) on top of `ConsentFlow`. A token works only
for the purpose it was minted for; an unknown purpose answers exactly like an
unknown link; a `names` detail reduces people to display names, so personal
codes never reach the unauthenticated page.

## Security posture

End-to-end Keycloak authentication, authorization, and a single seeded user:

- **Login.** The SPA boots inside `<AuthProvider>` which calls
  `keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256' })`. Anonymous
  users are redirected to Keycloak's login form. There is no unauthenticated
  view of the app.
- **Token attachment.** Every `/engine-rest/*` request from
  `frontend/src/api/camundaClient.ts` carries `Authorization: Bearer <jwt>`.
  `keycloak-js` refreshes the access token if it expires within 30 seconds.
- **JWT validation.** `RestApiSecurityConfig` (the plugin's reference example,
  copied verbatim) wires `spring-boot-starter-oauth2-resource-server` against
  Keycloak's JWKS; signature, expiry, issuer, and the `cib7-rest-api` audience
  are all checked. Anonymous calls get 401.
- **Engine identity binding.** `KeycloakAuthenticationFilter` extracts
  `preferred_username` from the validated JWT, looks up the user's groups via
  the identity provider plugin, and calls `IdentityService.setAuthentication`
  on the request thread. The `finally` clears it.
- **Authorization.** `camunda.bpm.authorization.enabled: true`. The applicant
  task in each BPMN is `camunda:assignee="${initiator}"`
  (only the applicant who started the case can complete it on the initial
  submit and on any send-back loop); the review task is
  `camunda:candidateGroups="civil-servant"` (only members of the
  back-office group can claim/complete it). Engine grants only add access,
  so no group holds a wildcard task grant: `AuthorizationBootstrap` lets
  applicants list and start services and lets civil servants read every case
  and retry jobs, and nothing more. An applicant reaches their own case
  through per-instance grants `InitiatorAuthorizationListener` creates when
  they start it (with `enableHistoricInstancePermissions` on, these also
  cover the case history), and works a task only through the engine's
  default authorization for its assignee or candidate group. Another
  applicant gets empty results or 403 for the same ids; a civil servant
  cannot complete an applicant's task. `admin` is in `/cib7-admin` (engine
  admin via the `cibseven-keycloak` plugin's `administratorGroupName`);
  Homer is an ordinary `/civil-servant`. Engine
  group ids in candidateGroups / authorization grants are the *slash-less*
  form (`applicant`, `civil-servant`, `cib7-admin`) — the cibseven-keycloak
  plugin strips the leading slash from the Keycloak group path even with
  `useGroupPathAsCamundaGroupId: true`. See
  [`cib7.md` § BPMN files](cib7.md#bpmn-files) for the full note.
- **Reserved expression names.** `busBaseUrl`, `frontendBaseUrl` and `pdf`
  resolve to their Spring beans before any process variable of the same
  name, in JUEL and in FreeMarker (`ReservedBeansPlugin`), so a client
  cannot redirect a case's connectors by writing a variable.
- **Engine to bus.** Every http-connector call to `${busBaseUrl}` carries
  `X-Bus-Token` (`BusTokenInterceptor`, env `BUS_TOKEN`); the bus refuses
  calls without it and strips it before forwarding.
- **`/api/**` trust levels (backend).** One Spring Security chain per
  endpoint class ([`security.md` rule 5](security.md#5-backend-endpoint-classes)):
  `/api/public/**` is unauthenticated by design (the capability token in the
  URL is the credential, or the data is read-only reference data with no
  personal data, like the vehicle catalog). Capability tokens are
  HMAC-signed by the engine's `links` bean (`CapabilityLinks`) into the
  email links, keyed with `LINK_SIGNING_SECRET`, and never stored; the
  backend's `CapabilityLinkVerifier` checks signature, expiry, purpose and
  the case's current consent round, and every failure is one 404
  ([rule 3](security.md#3-capability-links)). The one public endpoint without
  a capability token, `POST /api/public/payments/callback`, accepts only a
  body signed with `PAYMENT_PROVIDER_SECRET` for the amount the server
  charged ([rule 4](security.md#4-external-facts-need-proof)); `/api/internal/**` holds every
  endpoint only the engine calls and accepts only the shared
  `X-Internal-Token` header, which the integration bus injects after checking
  the engine's `X-Bus-Token` (the ingress never routes this prefix);
  `/api/documents/**` and `/api/cases/**` are a JWT resource server
  validating the same issuer + `cib7-rest-api` audience as the engine. A
  final deny-all chain refuses any other path. Storage keys a client hands
  in are accepted only under its own `pending/<user>/` prefix or the case's
  `process/<piId>/` prefix, and the engine's `move-pending` only moves keys
  under the case initiator's pending prefix.
- **`/engine-rest` is still directly exposed.** This POC has no BFF — the spec
  calls for one (see
  [`human-role-react-forms-spec.md` §D11](human-role-react-forms-spec.md)).
  With Bearer-token auth + the resource-server filter chain in front of the
  engine, every call is authenticated and audited via the engine's history
  tables, which is the production-acceptable middle ground until a BFF is
  added.
- **No HTTPS.** Compose serves plain HTTP on `:3000`, `:8080`, and `:8180` —
  fine for local dev, not for the network. Production deploys need TLS in
  front of Keycloak and the SPA/backend.

See the [Authentication and authorization](cib7.md#authentication-and-authorization)
section of `cib7.md` for the engine wiring detail and
[Authentication](frontend.md#authentication) in `frontend.md` for the SPA
side.

## Known trade-offs vs. the spec

The full deviations table lives in the top-level
[`README.md`](../README.md#deviations-from-the-spec) — read it there. The
three with architectural impact (rather than just developer ergonomics) are
**no BFF in front of `/engine-rest`**, **no form-manifest validation at
publish time**, and **plain typed variables instead of a single `json` Spin
variable**. Anything beyond that — auth, `cib:` vs `camunda:` namespace,
edit/view form split — is in the README's table.
