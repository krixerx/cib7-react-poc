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

**Repo shape — monorepo.** Backend (`backend/`), frontend (`frontend/`), the
vendored connector JAR (`lib/`), Docker orchestration (`docker-compose.yml`),
and the docs (`docs/`) all live in one git repository. They are versioned,
built, and released together — one branch / one PR can change both sides of
the React ↔ engine boundary atomically, which is the whole point.

```
  Browser (React SPA) ──/engine-rest──▶  CIB seven 2.1 engine + REST API
   (nginx in prod,                       (Spring Boot, embedded engine,
    Vite proxy in dev)                    in-memory H2)
                                              │
                                              │ rest-datasonnet connector
                                              ▼
                                       api.restful-api.dev
                                       (external REST API)
```

Three runtime pieces:

- **React SPA** — single-page app, talks only to the same-origin path
  `/engine-rest/...`. No direct calls to `api.restful-api.dev` from the browser
  *except* the product picker in the first form (read-only listing).
- **Spring Boot backend** — embeds the CIB seven 2.1 process engine and exposes
  `/engine-rest`. Auto-deploys every BPMN on the classpath at startup.
- **External API** — `api.restful-api.dev`, called *server-side* from the
  engine's "Get price" service task via the rest-datasonnet connector.

## Components

| Component | Tech | Where | Purpose |
|---|---|---|---|
| React SPA | React 18 + TypeScript + Vite + React Router 6 | `frontend/` | Services / Tasks / TaskDetail pages, hand-written forms |
| HTTP server (prod) | nginx | `frontend/nginx.conf` | Serves built SPA, proxies `/engine-rest/` to the backend container |
| Dev server | Vite | `frontend/vite.config.ts` | Serves SPA in dev, proxies `/engine-rest` to `localhost:8080` |
| Backend app | Spring Boot 3.5, CIB seven 2.1 starter | `backend/` | Embedded engine + REST API |
| Process engine | CIB seven 2.1 (Camunda 7 fork) | starter dep | Executes BPMN, exposes `/engine-rest` |
| Connect plugin | `cibseven-engine-plugin-connect` | wired in `ConnectorConfiguration.java` | Enables `<camunda:connector>` service tasks |
| Connector | `rest-datasonnet-connector` (vendored in `lib/`) | declared in `backend/pom.xml` | HTTP GET/POST + DataSonnet response mapping |
| Database | H2 (in-memory) | runtime classpath, no datasource config | Engine state — wiped on every restart |
| Container orchestration | Docker Compose | `docker-compose.yml` | Two services: `backend`, `frontend` |

Detailed file-level wiring lives in [`frontend.md`](frontend.md) and
[`backend.md`](backend.md).

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
     Connector applies DataSonnet `payload.data.price`
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

| Environment | SPA origin | Backend | How `/engine-rest` reaches backend |
|---|---|---|---|
| Docker (`docker compose up`) | `http://localhost:3000` (nginx) | `http://localhost:8080` (exposed) | nginx `location /engine-rest/ { proxy_pass http://backend:8080; }` |
| Local dev | `http://localhost:5173` (Vite) | `http://localhost:8080` (`mvn spring-boot:run`) | Vite `server.proxy['/engine-rest']` → `http://localhost:8080` |

The SPA **always uses the same-origin path** `/engine-rest/...`. That removes
CORS from all engine traffic — no `Access-Control-*` config on the backend.
(One browser call still crosses origins: `objectsApi.ts` fetches the product
list from `api.restful-api.dev`. That public API serves the required CORS
headers, so it works without our involvement.)

## Deployment topology

`docker-compose.yml` defines two services:

- **backend** — built from `backend/Dockerfile`. Build context is the **repo
  root** (not `backend/`) so the build sees `lib/` as well — needed because the
  connector JAR is resolved from `file://${project.basedir}/../lib`. Publishes
  port `8080`.
- **frontend** — built from `frontend/Dockerfile` (multi-stage: Vite build →
  nginx). Publishes port `3000` mapped to container port `80`. `depends_on:
  backend` (start ordering only — nginx does not wait for the backend to be
  healthy).

There is no shared volume; the backend has no persistent volume because the
database is in-memory.

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

## Security posture (POC)

This POC is deliberately unauthenticated:

- No login. Anyone reaching the SPA can start a process and complete any task.
- No authorization. The engine is configured with defaults — no candidate
  groups, no IdP integration.
- No HTTPS. The compose setup serves plain HTTP on `:3000` and `:8080`.
- `/engine-rest` is **directly exposed**: the spec calls for a BFF in front of
  the engine; this POC skips it (see
  [`human-role-react-forms-spec.md` §D11](human-role-react-forms-spec.md)).

Do **not** point this at a real network without first adding auth + a BFF.

## Known trade-offs vs. the spec

The full deviations table lives in the top-level
[`README.md`](../README.md#deviations-from-the-spec) — read it there. The
three with architectural impact (rather than just developer ergonomics) are
**no BFF in front of `/engine-rest`**, **no form-manifest validation at
publish time**, and **plain typed variables instead of a single `json` Spin
variable**. Anything beyond that — auth, `cib:` vs `camunda:` namespace,
edit/view form split — is in the README's table.
