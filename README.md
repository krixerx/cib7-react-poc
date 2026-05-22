# CIB seven 2.1 + React — Human Tasks POC

A proof of concept: a [CIB seven](https://cibseven.org) 2.1 process engine runs
a BPMN process with **two human tasks** and a **connector-backed service task**,
and a **React** app opens each human task with its own hand-written form.

It is a slice of the larger design in
[`docs/human-role-react-forms-spec.md`](docs/human-role-react-forms-spec.md) —
see [Deviations from the spec](#deviations-from-the-spec) below.

---

## What it does

```
Person Registration (BPMN)

  start
    │
    ▼  Submit personal details   user task     (react:personal-details)
    │    first / last name, age, and a product picked from api.restful-api.dev
    │
    ▼  Get price                 service task  (rest-datasonnet connector)
    │    GET api.restful-api.dev/objects/{id} — DataSonnet maps data.price → price
    │
    ▼  Review application        user task     (react:review-application)
    │    shows the submitted data + the fetched price (read-only); Approve / Reject
    │
    ▼  Approved?  ── exclusive gateway ──▶  end
```

1. **Submit personal details** — a React form collects first name, last name,
   age, and a product chosen from `api.restful-api.dev`. The product's id is
   written to the `objectId` process variable.
2. **Get price** — a service task using the
   [rest-datasonnet connector](#service-task--the-rest-datasonnet-connector)
   calls `GET https://api.restful-api.dev/objects/{objectId}` and maps
   `data.price` from the response into the `price` variable with an inline
   DataSonnet script.
3. **Review application** — a React form shows the submitted data and the
   fetched `price` read-only, and lets a reviewer **Approve** or **Reject**
   (writing the `decision` variable).
4. An exclusive gateway branches on `decision` and the process ends.

## Architecture

```
  React SPA  ──/engine-rest──▶  CIB seven 2.1 engine + REST API
  (nginx / Vite proxy)          (Spring Boot, embedded, in-memory H2)
                                       │
                                       ▼  rest-datasonnet connector
                                  api.restful-api.dev   (external REST API)
```

- The browser only ever calls the same-origin path `/engine-rest/...`.
  In Docker, **nginx** proxies it to the backend; in dev, the **Vite** dev
  server does. No CORS configuration and no authentication — per the POC scope.
- The BPMN file lives in the backend and is **auto-deployed on startup**.
- The **Get price** service task calls the external API server-side, from the
  engine — via the rest-datasonnet connector.
- The database is **in-memory H2** — all data is lost when the backend stops.

## Project layout

```
cib7-react-poc/
├── docker-compose.yml
├── lib/                            project-local Maven repo — the connector JAR
├── backend/                        CIB seven 2.1 Spring Boot app
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/poc/cib7/
│       │   ├── Cib7PocApplication.java
│       │   └── ConnectorConfiguration.java   registers the Connect plugin
│       └── resources/
│           ├── application.yaml
│           └── processes/person-registration.bpmn
└── frontend/                       React + TypeScript + Vite app
    └── src/
        ├── api/
        │   ├── camundaClient.ts        typed /engine-rest client
        │   ├── bpmn.ts                 reads user tasks from BPMN XML
        │   └── objectsApi.ts           product list from api.restful-api.dev
        ├── pages/                      Services / Tasks / TaskDetail pages
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

On the **Services** page, pick a service to start a process and fill in the
applicant form. Then open the **Tasks** page — the service's human tasks are
shown as groups, with the active process instances waiting at each. Open one
to validate and complete it.

## Run locally (without Docker)

Requires **Java 17+** and **Node.js 20+**.

**Backend** (terminal 1):

```bash
cd backend
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

## Service task & the rest-datasonnet connector

The **Get price** service task uses the
[rest-datasonnet connector](https://github.com/krixerx/cib7-rest-datasonnet-connector)
— a CIB seven Connect SPI connector that calls a REST API and maps the response
with [DataSonnet](https://datasonnet.com). It is wired in three places:

- **Connector JAR** — the connector is not yet published to a public Maven
  repository, so its built JAR is vendored in `lib/`, a project-local Maven
  repository in standard layout. `backend/pom.xml` declares `lib/` as a
  `<repository>` and the connector as a normal `<dependency>`. When the
  connector is published, delete `lib/` and the `<repository>` block — the
  `<dependency>` is unchanged.
- **Connect plugin** — `ConnectorConfiguration` registers
  `ConnectProcessEnginePlugin` so the engine parses `<camunda:connector>`.
- **BPMN** — the service task carries the connector config inline, including
  the DataSonnet response mapping:

  ```xml
  <camunda:connector>
    <camunda:connectorId>rest-datasonnet</camunda:connectorId>
    <camunda:inputOutput>
      <camunda:inputParameter name="url">https://api.restful-api.dev/objects/${objectId}</camunda:inputParameter>
      <camunda:inputParameter name="method">GET</camunda:inputParameter>
      <camunda:inputParameter name="responseMapping"><![CDATA[/** DataSonnet version=2.0 */
  payload.data.price
  ]]></camunda:inputParameter>
      <camunda:outputParameter name="price">${result}</camunda:outputParameter>
    </camunda:inputOutput>
  </camunda:connector>
  ```

The service task runs `asyncBefore`, so after the first form is confirmed the
job executor runs the connector — the **Review application** task appears a
moment later (use the Tasks page **Refresh** button).

> **Note on DataSonnet + Spring Boot 3.** DataSonnet 2.5.2's Java format plugin
> loads `javax.xml.bind` classes. Spring Boot 3's dependency management upgrades
> the connector's transitive JAXB to the Jakarta-4 namespace, so the backend
> pins the standalone javax JAXB 2.3.1 explicitly (see `backend/pom.xml`).

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
| BFF between React and engine (D11) | React calls **`/engine-rest` directly** | Requested scope: a simple POC, no auth. |
| Form manifest + publish-time validation (§11) | Omitted | The BPMN is a single static file, not dynamically generated. |
| Single `json` Spin variable (§10) | Plain typed variables (`firstName`, `objectId`, `price`, `decision`, …) | Simpler; no Spin needed for a POC. |
| Separate edit/view form components (§8.2–8.3) | One component per form | The "entry then review" flow already gives one edit form and one read-only review form. |
| IdP groups → candidate groups | No authentication | Requested scope. |

For a production system the spec's BFF, manifest validation and authorization
would be reinstated.

## Notes & limitations

- In-memory H2 means **process state is lost on backend restart**.
- No authentication: anyone who can reach the app can start and complete tasks.
- The **Get price** service task calls the public `api.restful-api.dev` — an
  internet connection is needed for that step.
- The connector JAR in `lib/` is a vendored build; rebuild it from
  [its repository](https://github.com/krixerx/cib7-rest-datasonnet-connector)
  when the connector changes.
- The CIB seven web apps (Cockpit / Tasklist / Admin) are **not** included. To
  add them, add the `cibseven-bpm-spring-boot-starter-webapp` dependency.
