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

**Module split.** `cib7/` is a *clean engine*: CIB seven 2.1 + plugins +
connectors + BPMN/DMN/FreeMarker resources, no business endpoints. Every
`/api/**` surface (public owner-confirmation / founder-signature / payment
links, the vehicle-registry stand-in, document storage) lives in `backend/`,
a separate Spring Boot 4 microservice that reaches the engine exclusively
over `/engine-rest` using the `cib7-business` Keycloak service account.

```
  Browser (React SPA) ──OIDC login──▶ Keycloak
   │  (PKCE)
   │  Bearer JWT
   ├─ /engine-rest ───▶  CIB seven 2.1 engine + REST API     (cib7/, in-memory H2)
   │                     │
   │                     │  http-connector
   │                     ├──▶ backend /api/...           (vehicle lookup, document
   │                     │                                move-pending/server-upload)
   │                     ├──▶ Mailpit /api/v1/send       (email + attachments)
   │                     └──▶ pdf-renderer /render ──▶ Gotenberg (Chromium → PDF)
   │
   └─ /api/... ───────▶  backend — business microservice  (backend/, Spring Boot 4)
                          │  public confirmations / payments / vehicle registry,
                          │  documents (JPA metadata + S3 presigned URLs)
                          ├──▶ /engine-rest  (cib7-business service account)
                          └──▶ RustFS (S3)   ◀── browser presigned PUT/GET

                       mcp sidecar ◀── Claude Desktop / Cursor / Codex
                       (Bearer-proxy → /engine-rest, documents → backend /api)
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
  2.1 process engine, exposing `/engine-rest` and the legacy `/camunda`
  webapps. Auto-deploys every BPMN/DMN on the classpath at startup. Validates
  JWTs as an OAuth2 resource server and bridges the authenticated user into
  the engine's `IdentityService` per request. Contains no business REST
  endpoints — only engine, plugins, connectors, and process resources.
- **Business microservice (`backend/`)** — Spring Boot 4 app owning every
  `/api/**` surface: the public token-link endpoints
  (`/api/public/owner-confirmations`, `/api/public/founder-signatures`,
  `/api/public/payments`), the curated vehicle registry
  (`/api/public/vehicle-registry`, the Liiklusregister stand-in the engine
  calls via http-connector), and `/api/documents` (S3 presigned upload /
  download against RustFS; metadata as a JPA `Document` entity in its own
  in-memory H2). Talks to the engine only over `/engine-rest`, authenticated
  with the `cib7-business` Keycloak service account (client_credentials; the
  service account sits in `/cib7-admin` so engine authorization passes).
- **RustFS** — S3-compatible object storage for applicant uploads and
  engine-generated PDFs. The backend mints presigned PUT/GET URLs so the
  browser moves bytes directly; port `9000` stays host-published because S3
  signature v4 hashes the host header.
- **Mailpit** — local SMTP+HTTP test server. The engine POSTs notifications
  (reminder, send-back, approval) to its `/api/v1/send` endpoint; the inbox
  at `:8025` renders them with attachments.
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
  `docs/business/services/<svc>/build/mcp-service.json` generated by
  `/service-builder`. Full module guide: [`mcp.md`](mcp.md).

## Components

| Component | Tech | Where | Purpose |
|---|---|---|---|
| React SPA | React 18 + TypeScript + Vite + React Router 6 | `frontend/` | Services / Tasks / TaskDetail pages, hand-written forms |
| SPA auth client | `keycloak-js` | `frontend/src/auth/` | OIDC PKCE login + token refresh; gates every route |
| Ingress (prod compose) | Traefik v3.4 | `docker-compose.yml` (`traefik` service) | Single public front door on `:3000`; path-routes `/engine-rest`, `/camunda`, `/oauth2`, `/login`, `/logout` → cib7; `/api` → backend; `/mcp`, `/.well-known/oauth-protected-resource` → mcp; everything else → frontend. The engine, backend, MCP, frontend, and Mailpit are network-internal — only Traefik, Keycloak, and RustFS publish host ports. |
| HTTP server (prod) | nginx | `frontend/nginx.conf` | Serves built SPA. Cross-service routing has moved to Traefik; this nginx only does the SPA fallback (`try_files $uri /index.html`). |
| Dev server | Vite | `frontend/vite.config.ts` | Serves SPA in dev, proxies `/engine-rest` to `localhost:8080`. (Vite-dev does not use Traefik; the engine still binds 8080 when run via `mvn spring-boot:run`.) |
| Engine app | Spring Boot 3.5, CIB seven 2.1 starter | `cib7/` | Embedded engine + REST API; no business endpoints |
| Business microservice | Spring Boot 4 (webmvc + data-jpa + security) | `backend/` | All `/api/**`: public confirmation/payment links, vehicle registry, documents (JPA `Document` metadata + S3 presigner); engine access via `/engine-rest` with the `cib7-business` service account |
| Object storage | RustFS (S3-compatible) | compose service | Applicant uploads + generated PDFs under `process/{piId}/…`; presigned URLs minted by the backend |
| Process engine | CIB seven 2.1 (Camunda 7 fork) | starter dep | Executes BPMN, exposes `/engine-rest` |
| Connect plugin | `cibseven-engine-plugin-connect` | wired in `ConnectorConfiguration.java` | Enables `<camunda:connector>` service tasks |
| Connector | `cibseven-connect-http-client` (official `http-connector`) | declared in `cib7/pom.xml` | HTTP request via Apache HttpClient 5; response body parsed inline with Spin |
| Identity provider plugin | `cibseven-keycloak` 2.1.0 | wired in `com/poc/cib7/keycloak/KeycloakIdentityProvider.java` | `ReadOnlyIdentityProvider`: engine reads users/groups from Keycloak |
| REST API security | Spring Security OAuth2 Resource Server | `com/poc/cib7/keycloak/RestApiSecurityConfig.java` (verbatim from plugin's `sso-kubernetes` example) | Validates Bearer JWTs and pushes user into `IdentityService` per request |
| Engine authorization bootstrap | `com/poc/cib7/AuthorizationBootstrap.java` | local | Grants the `applicant` engine group the narrow set of permissions it needs (admins are handled by the plugin's `administratorGroupName`) |
| Identity provider | Keycloak 26 | `keycloak/realm-export.json` + compose service | OIDC; pre-seeded realm `cib7-poc` with two users: `bart` / `bart` (applicant — PartA) and `homer` / `homer` (civil servant + admin — PartB) |
| Database | H2 (in-memory) | runtime classpath, no datasource config | Engine state — wiped on every restart |
| Email sink | Mailpit | compose service | Captures every notification + attachment the process sends; UI at `:8025` |
| PDF generator | Gotenberg 8 (headless Chromium) | compose service | Internal only — converts HTML → PDF over multipart REST |
| PDF adapter | `pdf-renderer/` (Node + Express, 20 LOC) | local module, compose service | JSON-in / JSON-out facade over Gotenberg so the http-connector stays plain HTTP + JSON |
| MCP sidecar | `mcp/` (Node + TypeScript + Express + `@modelcontextprotocol/sdk` + `jose`) | local module, compose service | Streamable HTTP MCP transport at `/mcp`; OAuth2 PKCE-loopback against Keycloak; JOSE jwtVerify at the door; Bearer-forwards to `/engine-rest`; Ajv-validates inputs against per-service manifests; uses `cib7-backend` service account for invitation emails |
| Per-service MCP manifests | `docs/business/services/<svc>/build/mcp-service.json` (generated) | `/service-builder` skill | Variable schemas + audience metadata; loaded by the MCP sidecar at startup |
| Aggregated MCP index | `docs/business/services/build/services.json` (generated) | `/service-builder` skill | Top-level catalog of MCP-callable services |
| Container orchestration | Docker Compose | `docker-compose.yml` | Ten services + ingress: `traefik`, `keycloak`, `cib7`, `backend`, `frontend`, `mailpit` (+ `mailpit-ui` in the `dev` profile), `gotenberg`, `pdf-renderer`, `rustfs`, `mcp` |

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
     SPA → looks up formKey "react:owner-vehicle" → OwnerVehicleForm
4. OwnerVehicleForm fetches the vehicle dropdown (browser → backend)
     SPA → GET /api/public/vehicle-registry/vehicles
   and stages the ID upload (browser → backend → RustFS)
     SPA → POST /api/documents/upload-url → presigned PUT direct to RustFS
5. User submits → SPA completes the task with typed variables
     SPA → POST /engine-rest/task/{taskId}/complete
     ↓
6. Engine promotes the staged upload + looks the vehicle up (job executor)
     Engine → POST {apiBaseUrl}/api/documents/move-pending   (X-Internal-Token)
     Engine → GET  {apiBaseUrl}/api/public/vehicle-registry/vehicles/{vin}
     Spin reads value/age inline; engine writes `price`, `vehicleAgeYears`
     ↓
7. DMN auto-approval policy decides: auto-approve, or create the
   "Transport Authority review" user task (candidateGroup civil-servant)
     Reviewer approves → {decision: "approve"} — or sends back to step 3
     ↓
8. Engine generates + stores the state-fee invoice PDF
     Engine → pdf-renderer /render → backend /api/documents/server-upload
   then parks on "Wait for state fee payment" (receive task)
     Payer → public /pay/{piId} page → POST /api/public/payments/{piId}/confirm
     Backend correlates PaymentReceived via /engine-rest/message
     ↓
9. Engine generates the registration certificate, process ends (Approved)
```

The full REST surface used by the SPA is listed in
[`frontend.md`](frontend.md#camunda-rest-endpoints-used).

## Ports, URLs, and proxying

| Environment | SPA origin | Engine + backend | Keycloak | How `/engine-rest` reaches the engine |
|---|---|---|---|---|
| Docker (`docker compose up`) | `http://localhost:3000` (Traefik) | network-internal (Traefik routes `/engine-rest`, `/camunda`, `/oauth2`, `/login`, `/logout` to `cib7:8080` and `/api` to `backend:8085`) | `http://localhost:8180` (exposed) | Traefik label on the `cib7` service: `PathPrefix("/engine-rest") || …` → `cib7-engine` loadbalancer on `8080` |
| Local dev | `http://localhost:5173` (Vite) | `http://localhost:8080` (`mvn spring-boot:run`) | `http://localhost:8180` (run Keycloak separately or via `docker compose up keycloak`) | Vite `server.proxy['/engine-rest']` → `http://localhost:8080` |

The SPA **always uses the same-origin paths** `/engine-rest/...` and
`/api/...`. That removes CORS from all engine and backend traffic — no
`Access-Control-*` config on either Java service. (One browser call still
crosses origins: document uploads/downloads go directly to RustFS on
`localhost:9000` with presigned URLs; the backend's `BucketBootstrap` sets
the bucket's CORS policy to the SPA origin for exactly that.)

## Deployment topology

`docker-compose.yml` defines ten services (plus the `dev`-profile
`mailpit-ui` sidecar):

- **keycloak** — `quay.io/keycloak/keycloak:26.1` in `start-dev --import-realm`
  mode. Mounts `keycloak/realm-export.json` so the realm boots pre-seeded
  (realm + clients + role + group + user). Publishes port `8180` mapped to
  container port `8080`. `KC_HOSTNAME_URL=http://localhost:8180` pins a
  single canonical issuer URL.
- **cib7** — built from `cib7/Dockerfile`. Build context is `./cib7`.
  Network-internal on port `8080` (reached through Traefik / the frontend
  nginx). Reaches Keycloak over the docker network at `http://keycloak:8080`
  (internal), while the browser uses `http://localhost:8180` (external) —
  see the "issuer-URL split" note below. Depends on `keycloak` (healthy),
  `mailpit` (started), and `pdf-renderer` (started); reads
  `MAIL_API_URL=http://mailpit:8025`, `PDF_API_URL=http://pdf-renderer:8088`,
  and `BACKEND_API_URL=http://backend:8085` (exposed to BPMN as
  `${apiBaseUrl}`) to drive the email, PDF, and document/vehicle-registry
  service tasks.
- **backend** — built from `backend/Dockerfile`. Network-internal on port
  `8085`; Traefik (and the frontend nginx fallback) route `/api` to it.
  Owns the `/api/**` business surface; depends on `keycloak` (healthy),
  `rustfs` (healthy), and `cib7` (started). Authenticates its
  `/engine-rest` calls with the `cib7-business` service account and shares
  `INTERNAL_TASK_TOKEN` with the engine for the two BPMN-called document
  endpoints (`move-pending`, `server-upload`).
- **rustfs** — S3-compatible object storage, host-published on `:9000` (the
  browser hits it directly with presigned URLs; S3 signature v4 hashes the
  host header, so it stays off the proxy). Bucket, CORS, and a 24h
  `pending/` lifecycle rule are bootstrapped by the backend on startup.
- **frontend** — built from `frontend/Dockerfile` (multi-stage: Vite build →
  nginx). Publishes port `3000` mapped to container port `80`. `depends_on:
  cib7` (start ordering only — nginx does not wait for the engine to be
  healthy).
- **mailpit** — `axllent/mailpit:latest`. Publishes `:8025` (web UI) and
  `:1025` (SMTP, unused — the engine uses the HTTP API).
- **gotenberg** — `gotenberg/gotenberg:8`. Headless Chromium wrapped in a
  REST API. Internal only (no host port mapping); only pdf-renderer talks
  to it on port 3000.
- **pdf-renderer** — built from `pdf-renderer/Dockerfile` (Node 20 +
  Express). Internal only on port 8088. JSON-in / JSON-out adapter in
  front of Gotenberg. Hides Gotenberg's multipart input format and binary
  output from the http-connector, which only handles plain
  `application/json` cleanly.
- **mcp** — built from `mcp/Dockerfile` with the repo root as build
  context (so the Dockerfile can COPY both `mcp/` source AND
  `docs/business/services/` for the per-service MCP manifests). Node 20 +
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

There is no shared volume between services; neither Java module has a
persistent volume because both databases are in-memory (the engine's process
state and the backend's `Document` metadata reset together on restart —
TODOS T1 tracks the shared move to Postgres). Keycloak uses its built-in dev
H2; RustFS bind-mounts `./.data/rustfs` in dev so uploaded bytes survive.

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

- The CIB seven engine uses an **in-memory H2** database. No `spring.datasource`
  is configured; the starter detects H2 on the classpath and provisions an
  in-memory instance. The `backend/` microservice uses the same pattern for
  its `Document` metadata table.
- **Implication:** restarting the engine wipes all process instances, tasks,
  history, and deployments (and restarting the backend wipes document
  metadata). The auto-deploy of BPMN files on startup is what
  makes the app usable again after a restart.
- `camunda.bpm.database.schema-update: true` lets the engine create its tables
  on first start.

If a future iteration needs persistence: add a real datasource (PostgreSQL is
the typical Camunda choice), remove the H2 runtime dep, set
`spring.datasource.*`, and add a volume in `docker-compose.yml`.

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
  back-office group can claim/complete it). Homer is in `/cib7-admin`
  (engine admin via the `cibseven-keycloak` plugin's
  `administratorGroupName`); Bart's narrower applicant permissions are
  bootstrapped at startup by
  `cib7/src/main/java/com/poc/cib7/AuthorizationBootstrap.java`. Engine
  group ids in candidateGroups / authorization grants are the *slash-less*
  form (`applicant`, `civil-servant`, `cib7-admin`) — the cibseven-keycloak
  plugin strips the leading slash from the Keycloak group path even with
  `useGroupPathAsCamundaGroupId: true`. See
  [`cib7.md` § BPMN files](cib7.md#bpmn-files) for the full note.
- **`/api/**` trust levels (backend).** Three Spring Security chains in
  `backend/`: `/api/public/**` is unauthenticated by design (the
  per-participant UUID token — or the opaque process-instance id for
  payments — in the URL is the credential); the two BPMN-called document
  endpoints accept only the shared `X-Internal-Token` header; everything
  else under `/api/documents/**` is a JWT resource server validating the
  same issuer + `cib7-rest-api` audience as the engine.
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
