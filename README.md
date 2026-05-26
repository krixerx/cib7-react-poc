# CIB seven 2.1 + React — Human Tasks POC

A proof of concept: a [CIB seven](https://cibseven.org) 2.1 process engine runs
a BPMN process with **two human tasks** and a **connector-backed service task**,
and a **React** app opens each human task with its own hand-written form.

It is a slice of the larger design in
[`docs/human-role-react-forms-spec.md`](docs/human-role-react-forms-spec.md) —
see [Deviations from the spec](#deviations-from-the-spec) below.

## Test logins

> **App:** <http://localhost:3000>
>
> **Applicant (PartA):** `bart` / `bart` — Bart Simpson, member of `/applicant`
>
> **Civil servant (PartB):** `homer` / `homer` — Homer Simpson, member of `/civil-servant` + `/cib7-admin`
>
> **Keycloak admin console:** <http://localhost:8180> — `admin` / `admin`

The SPA picks the role-appropriate UI from the JWT's realm roles:

- **PartA — applicant:** Services + My processes. Bart starts a process, fills
  the applicant form, and watches the status. If a civil servant sends the
  case back, the row's status shows "Sent back for corrections" and Bart can
  reopen the form (with the send-back reason shown as a banner) and resubmit.
- **PartB — back office:** Tasks + Incidents. Homer reviews the submitted
  application, then **Accept** (process ends approved) or **Send back…**
  (writes a reason variable and loops back to the applicant task).

Full realm in `keycloak/realm-export.json`.

---

## What it does

```
Person Registration (BPMN)

  start (initiator = applicant)
    │
    ▼  Submit personal details   user task   (applicant — PartA)
    │    first / last name, age, and a product picked from api.restful-api.dev
    │    assignee = ${initiator}
    │  ◀───────────────────────────────────────────────────────────┐
    ▼  Get price                 service task (http-connector)     │
    │    GET api.restful-api.dev/objects/{id} → data.price → price │
    │                                                              │
    ▼  Review application        user task   (civil servant — PartB)
    │    Accept → end approved                                     │
    │    Send back (with reason) ──────────────────────────────────┘
    │
    ▼  Decision?  ── exclusive gateway ──▶  end approved
```

1. **Submit personal details** (applicant) — a React form collects first name,
   last name, age, and a product chosen from `api.restful-api.dev`. The task
   is assigned to the starting user via `camunda:assignee="${initiator}"`, so
   only that applicant sees it.
2. **Get price** — a service task using the
   [`http-connector`](#service-task--the-http-connector)
   calls `GET https://api.restful-api.dev/objects/{objectId}` and reads
   `data.price` from the JSON response into the `price` variable via Spin.
3. **Review application** (civil servant) — a React form shows the submitted
   data and the fetched `price` read-only, and lets the reviewer **Accept**
   (sets `decision="approve"`) or **Send back** (sets `decision="sendback"`
   plus a `sendBackReason` variable).
4. An exclusive gateway branches on `decision`. `approve` ends the process;
   any other value loops back to the applicant task so they can fix the data
   based on the reason and resubmit.

## Architecture

```
  React SPA ──OIDC PKCE──▶ Keycloak ◀──Admin REST── CIB seven backend
   │   (keycloak-js)         │                      (identity provider plugin)
   │                         │
   │   Bearer JWT
   ▼
  /engine-rest  ──▶  CIB seven 2.1 engine + REST API
  (nginx / Vite proxy)  (Spring Boot, embedded engine, in-memory H2)
                                │
                                ▼  http-connector
                          api.restful-api.dev   (external REST API)
```

- The browser logs in against **Keycloak** (OIDC, PKCE) and then calls the
  same-origin path `/engine-rest/...` with a Bearer JWT on every request. In
  Docker, **nginx** proxies it to the backend; in dev, the **Vite** dev server
  does. No CORS configuration needed.
- The backend validates JWTs (Spring Security OAuth2 Resource Server) and the
  **CIB seven Keycloak Identity Provider Plugin** (`cibseven-keycloak` 2.1.0)
  reads users and groups from Keycloak's Admin REST API. Engine authorization
  is on, so `candidateGroups` on user tasks is enforced.
- The BPMN file lives in the backend and is **auto-deployed on startup**.
- The **Get price** service task calls the external API server-side, from the
  engine — via the official `http-connector`.
- The database is **in-memory H2** — all data is lost when the backend stops.

### Default credentials

The pre-seeded Keycloak realm (`keycloak/realm-export.json`) ships with:

- **Realm:** `cib7-poc`
- **`bart` / `bart`** (Bart Simpson) — `/applicant`, sees PartA.
- **`homer` / `homer`** (Homer Simpson) — `/civil-servant` + `/cib7-admin`,
  sees PartB. The admin group grants engine admin authorizations; the
  applicant group's narrower authorizations are bootstrapped on startup by
  `cib7/src/main/java/com/poc/cib7/AuthorizationBootstrap.java`.
- **Keycloak admin:** `admin` / `admin` (at <http://localhost:8180>) — only
  used for inspecting the realm; the app itself does not use it.

## Project layout

```
cib7-react-poc/
├── docker-compose.yml
├── cib7/                           CIB seven 2.1 Spring Boot engine module
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/poc/cib7/
│       │   ├── Cib7PocApplication.java
│       │   ├── ConnectorConfiguration.java   registers the Connect plugin
│       │   ├── AuthorizationBootstrap.java   grants /applicant engine perms
│       │   └── keycloak/                     Spring Security + Keycloak identity wiring
│       └── resources/
│           ├── application.yaml
│           └── processes/person-registration.bpmn
└── frontend/                       React + TypeScript + Vite app
    └── src/
        ├── api/
        │   ├── camundaClient.ts        typed /engine-rest client
        │   ├── bpmn.ts                 reads user tasks from BPMN XML
        │   └── objectsApi.ts           product list from api.restful-api.dev
        ├── pages/                      role-aware pages
        │   ├── ServicesPage.tsx        PartA — start a service
        │   ├── MyProcessesPage.tsx     PartA — applicant's instances + status
        │   ├── TasksPage.tsx           PartB — back-office task tree
        │   ├── IncidentsPage.tsx       PartB — open incidents + retry
        │   ├── TaskDetailPage.tsx      shared task form host
        │   └── CompletedProcessPage.tsx shared finished-process view
        └── forms/                      formKey → React component
```

---

## Run with Docker (recommended)

Requires Docker with Compose.

```bash
docker compose up --build
```

- React app → <http://localhost:3000>
- CIB seven REST API → <http://localhost:8080/engine-rest>
- Keycloak → <http://localhost:8180> (admin: `admin` / `admin`)

When the SPA loads it redirects to Keycloak's login form. Use `bart` / `bart`
to play the applicant or `homer` / `homer` to play the back-office reviewer.
Every `/engine-rest` call carries the JWT and the engine enforces
`candidateGroups` / `assignee` against the user's realm roles + group
membership.

As **Bart (PartA):** start a service on the **Services** page, fill the
applicant form, and watch the row appear under **My processes** with a live
status. If the back office sends the case back, reopen the row to see the
reason banner and resubmit.

As **Homer (PartB):** the **Tasks** page groups every service's user tasks
with the active instances waiting at each step. Open a review task, then
**Accept** (process ends) or **Send back** with a reason (loops to the
applicant).

## Run locally (without Docker)

Requires **Java 17+** and **Node.js 20+**.

**Engine** (terminal 1):

```bash
cd cib7
mvn spring-boot:run
```

**Frontend** (terminal 2):

```bash
cd frontend
npm install
npm run dev
```

Then open <http://localhost:5173>. The Vite dev server proxies `/engine-rest`
to the backend on port 8080.

---

## How the form wiring works

Each BPMN user task carries a `camunda:formKey`:

```xml
<bpmn:userTask id="Task_SubmitDetails" name="Submit personal details"
               camunda:formKey="react:personal-details" />
```

The React app reads the task's `formKey` from the REST API, strips the
`react:` prefix, and looks the form id up in `src/forms/registry.ts`:

```ts
export const formRegistry = {
  'personal-details':  PersonalDetailsForm,
  'review-application': ReviewApplicationForm,
};
```

**To add a form:** add a user task with a new `camunda:formKey` in the BPMN,
create the component under `src/forms/`, and add one registry entry.

## Service task & the http-connector

The **Get price** service task uses the official
[`cibseven-connect-http-client`](https://mvnrepository.com/artifact/org.cibseven.connect/cibseven-connect-http-client)
connector — a CIB seven Connect SPI connector that wraps Apache HttpClient 5.
It is wired in two places:

- **Connect plugin** — `ConnectorConfiguration` registers
  `ConnectProcessEnginePlugin` so the engine parses `<camunda:connector>`.
  The `cibseven-connect-http-client` dependency declared in `cib7/pom.xml`
  registers the connector itself through the Connect SPI.
- **BPMN** — the service task carries the connector config inline. The
  response body comes back as the `response` variable; Spin (bundled with the
  CIB seven engine) parses it inline to pull `data.price` out:

  ```xml
  <camunda:connector>
    <camunda:connectorId>http-connector</camunda:connectorId>
    <camunda:inputOutput>
      <camunda:inputParameter name="url">https://api.restful-api.dev/objects/${objectId}</camunda:inputParameter>
      <camunda:inputParameter name="method">GET</camunda:inputParameter>
      <camunda:inputParameter name="headers">
        <camunda:map>
          <camunda:entry key="Accept">application/json</camunda:entry>
        </camunda:map>
      </camunda:inputParameter>
      <camunda:outputParameter name="price">${S(response).prop('data').prop('price').numberValue()}</camunda:outputParameter>
    </camunda:inputOutput>
  </camunda:connector>
  ```

The service task runs `asyncBefore`, so after the first form is confirmed the
job executor runs the connector — the **Review application** task appears a
moment later (use the Tasks page **Refresh** button).

## REST endpoints used

All under `/engine-rest` (standard CIB seven / Camunda 7 REST API):

| Call | Purpose |
|------|---------|
| `GET  /process-definition?latestVersion=true` | List services (process definitions) |
| `GET  /process-definition/key/{key}/xml` | BPMN XML — read the model's user tasks |
| `POST /process-definition/key/{key}/start` | Start a process instance |
| `GET  /task` | List open tasks |
| `GET  /task?processInstanceId={id}` | Open tasks of one instance |
| `GET  /task/{id}` | Task details, including `formKey` |
| `GET  /task/{id}/form-variables` | Process variables for the form |
| `POST /task/{id}/complete` | Complete the task with typed variables |

---

## Deviations from the spec

This POC intentionally simplifies `docs/human-role-react-forms-spec.md`:

| Spec | This POC | Why |
|------|----------|-----|
| `cib:` BPMN namespace (§5.3) | Standard **`camunda:`** namespace | CIB seven 2.1 uses `camunda:` — confirmed against the official `cibseven-get-started-spring-boot` example. The spec's §5.3 is inaccurate. |
| BFF between React and engine (D11) | React calls **`/engine-rest` directly** with a Bearer JWT | Bearer auth + the resource-server filter chain in front of the engine is the production-acceptable middle ground until a BFF is added. |
| Form manifest + publish-time validation (§11) | Omitted | The BPMN is a single static file, not dynamically generated. |
| Single `json` Spin variable (§10) | Plain typed variables (`firstName`, `objectId`, `price`, `decision`, …) | Simpler; no Spin needed for a POC. |
| Separate edit/view form components (§8.2–8.3) | One component per form | The "entry then review" flow already gives one edit form and one read-only review form. |
| IdP groups → candidate groups | **Keycloak groups `/applicant` (assignee via `${initiator}`) and `/civil-servant` (candidateGroup `civil-servant` — slash stripped by the plugin)** | Implemented via `cibseven-keycloak` 2.1.0 with `useGroupPathAsCamundaGroupId: true`. The plugin maps path `/civil-servant` to engine group id `civil-servant`; see [`docs/cib7.md`](docs/cib7.md#bpmn-files). |

For a production system the spec's BFF and manifest validation would be
reinstated.

## Notes & limitations

- In-memory H2 means **process state is lost on backend restart**.
- Keycloak runs in `start-dev` mode with its own in-memory H2 — the realm is
  re-imported from `keycloak/realm-export.json` on every container start, so
  user-created users/groups are also lost on restart.
- The **Get price** service task calls the public `api.restful-api.dev` — an
  internet connection is needed for that step.
- The CIB seven web apps (Cockpit / Tasklist / Admin) are **not** included. To
  add them, add the `cibseven-bpm-spring-boot-starter-webapp` dependency.
