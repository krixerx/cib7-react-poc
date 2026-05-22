# CIB seven 2.1 + React — Human Tasks POC

A minimal proof of concept: a [CIB seven](https://cibseven.org) 2.1 process
engine runs a BPMN process with **two human tasks**, and a **React** app opens
each task with its own hand-written form.

It is a deliberately small slice of the larger design in
[`docs/human-role-react-forms-spec.md`](docs/human-role-react-forms-spec.md) —
see [Deviations from the spec](#deviations-from-the-spec) below.

---

## What it does

```
                        Person Registration (BPMN)
   ┌─────┐   ┌────────────────────────┐   ┌────────────────────┐   ┌──────────┐
   │start│──▶│ Submit personal details│──▶│ Review application │──▶│ Approved?│──▶ end
   └─────┘   │  (react:personal-      │   │  (react:review-    │   └──────────┘
             │   details)             │   │   application)     │
             └────────────────────────┘   └────────────────────┘
```

1. **Submit personal details** — a React form collects first name, last name
   and age, and completes the task (writing those process variables).
2. **Review application** — a React form shows that data read-only and lets a
   reviewer **Approve** or **Reject**, writing a `decision` variable.
3. An exclusive gateway branches on `decision` and the process ends.

## Architecture

```
  React SPA  ──/engine-rest──▶  CIB seven 2.1 engine + REST API
  (nginx / Vite proxy)          (Spring Boot, embedded, in-memory H2)
```

- The browser only ever calls the same-origin path `/engine-rest/...`.
  In Docker, **nginx** proxies it to the backend; in dev, the **Vite** dev
  server does. No CORS configuration and no authentication — per the POC scope.
- The BPMN file lives in the backend and is **auto-deployed on startup**.
- The database is **in-memory H2** — all data is lost when the backend stops.

## Project layout

```
cib7-react-poc/
├── docker-compose.yml
├── backend/                        CIB seven 2.1 Spring Boot app
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/poc/cib7/Cib7PocApplication.java
│       └── resources/
│           ├── application.yaml
│           └── processes/person-registration.bpmn
└── frontend/                       React + TypeScript + Vite app
    ├── package.json
    ├── Dockerfile
    ├── nginx.conf
    └── src/
        ├── api/
        │   ├── camundaClient.ts         typed /engine-rest client
        │   └── bpmn.ts                  reads user tasks from BPMN XML
        ├── pages/
        │   ├── ServicesPage.tsx         lists process definitions ("services")
        │   ├── TasksPage.tsx            human tasks grouped, with active instances
        │   └── TaskDetailPage.tsx       resolves formKey → renders the form
        └── forms/
            ├── registry.ts             formKey → React component
            ├── personal-details/PersonalDetailsForm.tsx
            └── review-application/ReviewApplicationForm.tsx
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
| BFF between React and engine (D11) | React calls **`/engine-rest` directly** | Requested scope: a very simple POC, no auth. |
| Form manifest + publish-time validation (§11) | Omitted | The BPMN is a single static file, not dynamically generated. |
| Single `json` Spin variable (§10) | Plain typed variables (`firstName`, `lastName`, `age`, `decision`) | Simpler; no Spin needed for a POC. |
| Separate edit/view form components (§8.2–8.3) | One component per form | The "entry then review" flow already gives one edit form and one read-only review form. |
| `lazy()` per form (§8.3) | Direct imports | Only two forms. |
| IdP groups → candidate groups | No authentication | Requested scope. |

For a production system the spec's BFF, manifest validation and authorization
would be reinstated.

## Notes & limitations

- In-memory H2 means **process state is lost on backend restart**.
- No authentication: anyone who can reach the app can start and complete tasks.
- The CIB seven web apps (Cockpit / Tasklist / Admin) are **not** included. To
  add them, add the `cibseven-bpm-spring-boot-starter-webapp` dependency.
