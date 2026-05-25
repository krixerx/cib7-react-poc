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

**Repo shape — monorepo.** CIB seven engine module (`cib7/`), frontend
(`frontend/`), the Keycloak realm export (`keycloak/`), Docker orchestration
(`docker-compose.yml`), and the docs (`docs/`) all live in one git repository. They are versioned, built, and
released together — one branch / one PR can change both sides of the React ↔
engine boundary atomically, which is the whole point.

```
  Browser (React SPA) ──OIDC login──▶ Keycloak
   │  (PKCE)
   │  Bearer JWT
   ▼
   /engine-rest ───▶  CIB seven 2.1 engine + REST API
   (nginx in prod,    (Spring Boot, embedded engine, in-memory H2)
    Vite proxy in dev) │
                       │  http-connector
                       ▼
                api.restful-api.dev   (external REST API)
                       ▲
                       │  identity provider plugin (Admin REST)
                       │
                    Keycloak
```

Four runtime pieces:

- **Keycloak** — OAuth2 / OIDC identity provider. Hosts the login form, issues
  JWT access tokens to the SPA, and exposes its Admin REST API to the backend's
  identity provider plugin.
- **React SPA** — single-page app, talks to Keycloak directly for login (via
  `keycloak-js`, PKCE), then to the same-origin path `/engine-rest/...` with a
  Bearer JWT on every call. Also makes the product picker call to
  `api.restful-api.dev` (read-only listing).
- **Spring Boot backend** — embeds the CIB seven 2.1 process engine and
  exposes `/engine-rest`. Auto-deploys every BPMN on the classpath at startup.
  Validates JWTs as an OAuth2 resource server and bridges the authenticated
  user into the engine's `IdentityService` per request.
- **External API** — `api.restful-api.dev`, called *server-side* from the
  engine's "Get price" service task via the official `http-connector`.

## Components

| Component | Tech | Where | Purpose |
|---|---|---|---|
| React SPA | React 18 + TypeScript + Vite + React Router 6 | `frontend/` | Services / Tasks / TaskDetail pages, hand-written forms |
| SPA auth client | `keycloak-js` | `frontend/src/auth/` | OIDC PKCE login + token refresh; gates every route |
| HTTP server (prod) | nginx | `frontend/nginx.conf` | Serves built SPA, proxies `/engine-rest/` to the backend container |
| Dev server | Vite | `frontend/vite.config.ts` | Serves SPA in dev, proxies `/engine-rest` to `localhost:8080` |
| Engine app | Spring Boot 3.5, CIB seven 2.1 starter | `cib7/` | Embedded engine + REST API |
| Process engine | CIB seven 2.1 (Camunda 7 fork) | starter dep | Executes BPMN, exposes `/engine-rest` |
| Connect plugin | `cibseven-engine-plugin-connect` | wired in `ConnectorConfiguration.java` | Enables `<camunda:connector>` service tasks |
| Connector | `cibseven-connect-http-client` (official `http-connector`) | declared in `cib7/pom.xml` | HTTP request via Apache HttpClient 5; response body parsed inline with Spin |
| Identity provider plugin | `cibseven-keycloak` 2.1.0 | wired in `com/poc/cib7/keycloak/KeycloakIdentityProvider.java` | `ReadOnlyIdentityProvider`: engine reads users/groups from Keycloak |
| REST API security | Spring Security OAuth2 Resource Server | `com/poc/cib7/keycloak/RestApiSecurityConfig.java` (verbatim from plugin's `sso-kubernetes` example) | Validates Bearer JWTs and pushes user into `IdentityService` per request |
| Identity provider | Keycloak 26 | `keycloak/realm-export.json` + compose service | OIDC; pre-seeded realm `cib7-poc` with one user (`homer` / `homer`) |
| Database | H2 (in-memory) | runtime classpath, no datasource config | Engine state — wiped on every restart |
| Container orchestration | Docker Compose | `docker-compose.yml` | Three services: `keycloak`, `cib7`, `frontend` |

Detailed file-level wiring lives in [`frontend.md`](frontend.md) and
[`cib7.md`](cib7.md).

## Request flow — happy path

A single "Person Registration" process instance:

```
1. User opens Services page
     SPA → GET /engine-rest/process-definition?latestVersion=true
2. User picks "Person Registration", clicks Start
     SPA → POST /engine-rest/process-definition/key/personRegistration/start
     SPA → GET  /engine-rest/task?processInstanceId={id}    (find first task)
     SPA navigates to /tasks/{taskId}
3. TaskDetail loads the task + variables, resolves the form
     SPA → GET /engine-rest/task/{taskId}
     SPA → GET /engine-rest/task/{taskId}/form-variables
     SPA → looks up formKey "react:personal-details" → PersonalDetailsForm
4. PersonalDetailsForm fetches the product list (browser → external API)
     SPA → GET https://api.restful-api.dev/objects
5. User submits → SPA completes the task with typed variables
     SPA → POST /engine-rest/task/{taskId}/complete
     ↓
6. Engine runs the "Get price" service task (asyncBefore — job executor)
     Engine → GET https://api.restful-api.dev/objects/{objectId}
     Connector returns the body; Spin reads `data.price` inline
     Engine writes the `price` process variable
     ↓
7. Engine creates the "Review application" user task
     User clicks Refresh on /tasks → sees the new task
8. ReviewApplicationForm renders read-only summary + price
     User clicks Approve/Reject
     SPA → POST /engine-rest/task/{taskId}/complete  ({decision: "approve"})
     ↓
9. Exclusive gateway routes by `decision`, process ends
```

The full REST surface used by the SPA is listed in
[`frontend.md`](frontend.md#camunda-rest-endpoints-used).

## Ports, URLs, and proxying

| Environment | SPA origin | Backend | Keycloak | How `/engine-rest` reaches backend |
|---|---|---|---|---|
| Docker (`docker compose up`) | `http://localhost:3000` (nginx) | `http://localhost:8080` (exposed) | `http://localhost:8180` (exposed) | nginx `location /engine-rest/ { proxy_pass http://cib7:8080; }` |
| Local dev | `http://localhost:5173` (Vite) | `http://localhost:8080` (`mvn spring-boot:run`) | `http://localhost:8180` (run Keycloak separately or via `docker compose up keycloak`) | Vite `server.proxy['/engine-rest']` → `http://localhost:8080` |

The SPA **always uses the same-origin path** `/engine-rest/...`. That removes
CORS from all engine traffic — no `Access-Control-*` config on the backend.
(One browser call still crosses origins: `objectsApi.ts` fetches the product
list from `api.restful-api.dev`. That public API serves the required CORS
headers, so it works without our involvement.)

## Deployment topology

`docker-compose.yml` defines three services:

- **keycloak** — `quay.io/keycloak/keycloak:26.1` in `start-dev --import-realm`
  mode. Mounts `keycloak/realm-export.json` so the realm boots pre-seeded
  (realm + clients + role + group + user). Publishes port `8180` mapped to
  container port `8080`. `KC_HOSTNAME_URL=http://localhost:8180` pins a
  single canonical issuer URL.
- **cib7** — built from `cib7/Dockerfile`. Build context is `./cib7`.
  Publishes port `8080`. Reaches Keycloak over the docker network at
  `http://keycloak:8080` (internal), while the browser uses
  `http://localhost:8180` (external) — see the "issuer-URL split" note below.
- **frontend** — built from `frontend/Dockerfile` (multi-stage: Vite build →
  nginx). Publishes port `3000` mapped to container port `80`. `depends_on:
  cib7` (start ordering only — nginx does not wait for the engine to be
  healthy).

There is no shared volume; the backend has no persistent volume because the
database is in-memory; Keycloak uses its built-in dev H2.

**Issuer-URL split.** Keycloak issues JWTs with an `iss` claim matching its
`KC_HOSTNAME_URL`, pinned here to `http://localhost:8180` — the URL the
browser uses. The backend, sitting in a docker container, can't reach
`localhost:8180` (that resolves to the backend itself). The standard
production pattern is to use two URLs:

| Role | URL | Used by |
|---|---|---|
| Public (`iss` claim, browser login redirects) | `http://localhost:8180` | Browser (`keycloak-js`), backend's `iss` validator |
| Internal (server-to-server) | `http://keycloak:8080` | Backend's identity provider plugin (Admin REST), backend's JWKS fetch |

The backend's `RestApiSecurityConfig.jwtDecoder()` builds a `NimbusJwtDecoder`
with `.withJwkSetUri(jwkSetUri)` (internal URL) and validates the `iss` claim
against the public URL with `JwtValidators.createDefaultWithIssuer` (a plain
string compare — no HTTP call). The plugin's Admin REST calls also use the
internal URL. Tokens minted by the same Keycloak carry `iss=localhost:8180`
regardless of which interface the backend talks to. If you change the
Keycloak hostname for production, change `KC_HOSTNAME_URL` (Keycloak),
`KEYCLOAK_ISSUER_URL` (backend, public URL), `KEYCLOAK_URL` (backend, internal
URL), and `frontend/src/auth/keycloak.ts` together.

## Data persistence

- The CIB seven engine uses an **in-memory H2** database. No `spring.datasource`
  is configured; the starter detects H2 on the classpath and provisions an
  in-memory instance.
- **Implication:** restarting the backend wipes all process instances, tasks,
  history, and deployments. The auto-deploy of BPMN files on startup is what
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
- **Authorization.** `camunda.bpm.authorization.enabled: true`. Both user
  tasks in `person-registration.bpmn` carry
  `camunda:candidateGroups="/task-executor"`. The user `homer` is a member of
  the `/task-executor` Keycloak group, so they can claim and complete the
  tasks; any other authenticated user would get 403.
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
