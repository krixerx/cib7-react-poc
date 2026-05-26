# Frontend

**When to read this:** before editing anything under `frontend/src/`; when
adding a new form, page, or REST call; when changing how a user task is rendered.

**Contents**
1. [Stack](#stack)
2. [File layout](#file-layout)
3. [Routing](#routing)
4. [Pages](#pages)
5. [Forms](#forms)
6. [REST client (`api/`)](#rest-client-api)
7. [Authentication](#authentication)
8. [Camunda REST endpoints used](#camunda-rest-endpoints-used)
9. [How to add a new form](#how-to-add-a-new-form)
10. [Dev server, build, typecheck](#dev-server-build-typecheck)
11. [Conventions](#conventions)

---

## Stack

| | |
|---|---|
| Language | TypeScript (strict — see `tsconfig.json`) |
| Framework | React 18 |
| Router | React Router 6 |
| Build / dev server | Vite 5 |
| Styling | Plain CSS in `src/styles.css` |
| HTTP | `fetch` (no axios / SWR / React Query) |
| Auth | `keycloak-js` (OIDC PKCE against Keycloak) |

There are no UI / form / state libraries. Forms are hand-written; state is local
React state. Keep it that way unless there is a concrete reason to add a
dependency.

## File layout

```
frontend/src/
├── main.tsx                       — bootstraps React + Router + AuthProvider
├── App.tsx                        — layout shell, role-based nav + routes
├── styles.css
├── vite-env.d.ts                  — Vite client types + VITE_KEYCLOAK_* env vars
├── auth/
│   ├── keycloak.ts                — keycloak-js singleton + ensureFreshToken()
│   └── AuthProvider.tsx           — login gate, useAuth() context (exposes role flags)
├── api/
│   ├── camundaClient.ts           — typed /engine-rest client + interfaces (attaches Bearer JWT)
│   ├── bpmn.ts                    — parseUserTasks(bpmnXml) → UserTaskDef[]
│   └── objectsApi.ts              — listPricedObjects() from restful-api.dev
├── pages/
│   ├── ServicesPage.tsx           — PartA route "/"
│   ├── MyProcessesPage.tsx        — PartA route "/my-processes"
│   ├── TasksPage.tsx              — PartB route "/"
│   ├── IncidentsPage.tsx          — PartB route "/incidents"
│   ├── TaskDetailPage.tsx         — shared route "/tasks/:taskId"
│   └── CompletedProcessPage.tsx   — shared route "/processes/:processInstanceId"
└── forms/
    ├── types.ts                   — FormProps contract
    ├── registry.ts                — formId → React component map
    ├── personal-details/PersonalDetailsForm.tsx
    └── review-application/ReviewApplicationForm.tsx
```

One folder per form, named after the form id. The form component lives inside.

## Routing

`App.tsx` reads `isCivilServant` from `useAuth()` and renders one of two
route sets. The TaskDetail and CompletedProcess pages are shared.

### PartA — applicant (`isCivilServant === false`)

| Path | Component | Purpose |
|---|---|---|
| `/` | `ServicesPage` | Pick a service and start a new instance |
| `/my-processes` | `MyProcessesPage` | The applicant's own instances + live status pill |

### PartB — civil servant / back office (`isCivilServant === true`)

| Path | Component | Purpose |
|---|---|---|
| `/` | `TasksPage` | Tree of services with active task counts + drill-down |
| `/incidents` | `IncidentsPage` | Open engine incidents across all services; retry |

### Shared

| Path | Component | Purpose |
|---|---|---|
| `/tasks/:taskId` | `TaskDetailPage` | Renders the React form for one task; completing it returns the user to their list (`/` for civil servants, `/my-processes` for applicants) |
| `/processes/:processInstanceId` | `CompletedProcessPage` | Read-only view of a finished instance — last user task's form pre-filled with historic variables |

A catch-all `*` route redirects to `/` so the role-appropriate landing page
always wins after a logout/login. There is no per-route role check beyond
which routes are rendered — the engine's authorization filter is the real
gate.

## Pages

Each page is described in terms of the client functions it calls — for the
underlying HTTP methods and paths, see the canonical
[endpoint table](#camunda-rest-endpoints-used).

### `ServicesPage` (`src/pages/ServicesPage.tsx`) — PartA

- Calls `listProcessDefinitions()` to populate the list.
- On Start: `startProcess(key)`, then `listTasksByInstance(instanceId)` to find
  the first user task, then navigates to `/tasks/{taskId}`. If the engine has
  raced past the first user task (e.g. a service task in flight), navigates
  to `/my-processes` instead.

### `MyProcessesPage` (`src/pages/MyProcessesPage.tsx`) — PartA

- `listHistoricProcessInstancesByStarter(username)` returns every instance
  the applicant started (active + finished, newest first).
- For each active instance: `listTasksByInstance(id)` + `getHistoricVariable(id, 'sendBackReason')`
  decide the status:

  | Active task | `sendBackReason` | Status pill |
  |---|---|---|
  | `Task_SubmitDetails` | empty / absent | **Awaiting submission** |
  | `Task_SubmitDetails` | non-empty | **Sent back for corrections** |
  | `Task_Review` | — | **Under review** |
  | none (service task in flight) | — | **Processing** |

- Finished instances are labelled **Approved** if `endActivityId === 'EndEvent_Approved'`,
  otherwise **Ended**.
- When the applicant task is active, the row links to `/tasks/{taskId}`;
  finished rows link to `/processes/{instanceId}` (read-only). Rows that are
  parked on a civil-servant step show the status pill only — they're not
  clickable because nothing's waiting on the applicant.

### `TasksPage` (`src/pages/TasksPage.tsx`) — PartB

- Loads `listProcessDefinitions()` and `listTasks()` in parallel.
- For each service, calls `getProcessDefinitionXml()` once and passes the XML
  through `parseUserTasks()` to extract the declared user-task ids/names. This
  is how we render every user task **even when no instance is sitting at it**.
- Renders a tree sidebar (service → its user tasks + Incidents row). The
  right pane drills down into a per-task list of active + historic instances.

### `IncidentsPage` (`src/pages/IncidentsPage.tsx`) — PartB

- Lists open incidents across every service via `listIncidents()`. For
  `failedJob` incidents, surfaces a **Retry** button that calls
  `setJobRetries(incident.configuration, 1)` so the job executor picks the
  job up again.

### `TaskDetailPage` (`src/pages/TaskDetailPage.tsx`) — shared

- Loads `getTask(taskId)` and `getTaskVariables(taskId)` in parallel.
- Unwraps `{value, type}` variables to plain values (the `unwrap` helper).
- Resolves the form via `parseFormId(task.formKey)` → `formRegistry[formId]`.
- Renders the form with `task`, `data`, `onComplete`, `submitting`, `readOnly` props.
- `onComplete` calls `completeTask(...)` and on success navigates back to
  the role's list (`/` for civil servants, `/my-processes` for applicants).

### `CompletedProcessPage` (`src/pages/CompletedProcessPage.tsx`) — shared

- Read-only view of a finished instance. Looks up the last historic user task
  + its formKey from the BPMN, renders the matching form with `readOnly`
  prefilled from `listHistoricVariables(id)`. Shows the outcome label parsed
  from `endActivityId`.

## Forms

### Contract — `FormProps` (`src/forms/types.ts`)

```ts
export interface FormProps {
  task: CamundaTask;                                    // current task
  data: Record<string, unknown>;                        // unwrapped variables
  onComplete: (variables: CamundaVariables) => Promise<void>;
  submitting: boolean;
  readOnly?: boolean;                                   // history view, no submit
}
```

A form is a React component of type `ComponentType<FormProps>`. It is
responsible for:

1. Rendering inputs / read-only fields from `data`.
2. Local validation before submit.
3. Calling `onComplete(variables)` with the **CIB seven typed variable shape**:
   `{ <name>: { value, type: 'String' | 'Integer' | 'Long' | 'Double' | 'Boolean' } }`.
4. Disabling its submit controls while `submitting` is true.
5. When `readOnly` is true (CompletedProcessPage), rendering everything
   disabled and hiding submit actions.

The spec separates edit vs. read-only forms (§8.2). This POC keeps a single
component per form; "review" forms render their fields read-only and still
complete with an outcome variable.

### Registry — `src/forms/registry.ts`

```ts
export const formRegistry: Record<string, ComponentType<FormProps>> = {
  'personal-details': PersonalDetailsForm,
  'review-application': ReviewApplicationForm,
};

export function parseFormId(formKey: string | null | undefined): string | null {
  // "react:personal-details" → "personal-details"
}
```

### Existing forms

| Form id | Component | Reads | Writes |
|---|---|---|---|
| `personal-details` | `PersonalDetailsForm.tsx` | `firstName`, `lastName`, `age`, `objectId`, `sendBackReason` (shown as a banner on re-submit) + product list from `restful-api.dev` | `firstName: String`, `lastName: String`, `age: Integer`, `objectId: String`, `sendBackReason: ''` (cleared on resubmit) |
| `review-application` | `ReviewApplicationForm.tsx` | `firstName`, `lastName`, `age`, `price`, `sendBackReason` (read-only) | **Accept:** `decision: 'approve'` / **Send back:** `decision: 'sendback'` + `sendBackReason: String` |

## REST client (`api/`)

### `camundaClient.ts`

Thin typed wrapper around `fetch`. All calls go through `request<T>(path, init)`.

Exported types: `ProcessDefinition`, `CamundaTask`, `CamundaVariable`,
`CamundaVariables`, `CamundaVariableType`, `Incident`,
`HistoricProcessInstance`, `HistoricTask`, `HistoricVariableInstance`.

Exported functions: `listProcessDefinitions`, `getProcessDefinitionXml`,
`startProcess`, `listTasks`, `listTasksByInstance`, `getTask`,
`getTaskVariables`, `completeTask`, `listIncidents`,
`countActiveProcessInstances`, `setJobRetries`,
`listFinishedProcessInstances`, `listHistoricProcessInstancesByStarter`,
`getHistoricProcessInstance`, `listHistoricTasks`,
`listHistoricTasksByDefinition`, `listHistoricVariables`,
`getHistoricVariable`.

Conventions:

- Same-origin path: `const BASE = '/engine-rest'`.
- 204 No Content is treated as `undefined`.
- Non-2xx throws `Error` with `status + body`. Pages catch and render the message.

### `bpmn.ts`

Just one export: `parseUserTasks(bpmnXml)`. Uses `DOMParser` and matches by
`localName === 'userTask'`, so it works regardless of the BPMN namespace prefix
(`bpmn:userTask` vs. `userTask`).

### `objectsApi.ts`

`listPricedObjects()` calls `https://api.restful-api.dev/objects` directly
**from the browser** and filters to objects whose `data.price` is set. This is
the only browser call to the external API — the actual price *fetch* happens
server-side in the engine.

## Authentication

The SPA authenticates against Keycloak using OIDC PKCE via `keycloak-js`.
There is no unauthenticated view of the app.

### Files

| File | Responsibility |
|---|---|
| `src/auth/keycloak.ts` | Single `Keycloak` instance keyed by `VITE_KEYCLOAK_*` env (defaults: `http://localhost:8180`, realm `cib7-poc`, client `cib7-frontend`). Exports `ensureFreshToken()` that calls `updateToken(30)` and returns the current access token. |
| `src/auth/AuthProvider.tsx` | Calls `keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256' })` once, renders a loading state until it resolves. Provides `useAuth()` which exposes `{ username, realmRoles, isApplicant, isCivilServant, logout }` — `realmRoles` is `realm_access.roles` from the access token; the booleans drive PartA/PartB routing in `App.tsx`. A user with both `applicant` and `civil-servant` roles (e.g. an admin) is treated as a civil servant. Idempotent against React 18 StrictMode double-invoke via `keycloak.didInitialize`. |
| `src/main.tsx` | Wraps `<App />` in `<AuthProvider>`; nothing inside it renders until login succeeds. |
| `src/App.tsx` | Reads `username`, `isCivilServant`, and `logout` from `useAuth()`; renders the Part A / Part B nav and route set accordingly. |
| `src/api/camundaClient.ts` | `request()` awaits `ensureFreshToken()` and attaches `Authorization: Bearer <jwt>` to every `/engine-rest/*` call. |

### Flow

1. SPA loads. `<AuthProvider>` mounts and triggers `keycloak.init`.
2. If no session: Keycloak redirects to its hosted login form.
3. After login, Keycloak redirects back; `keycloak-js` exchanges the code (PKCE) for an access token + refresh token in memory.
4. `<AuthProvider>` flips to ready; the rest of the app renders.
5. Every `/engine-rest/*` request awaits `keycloak.updateToken(30)` first, so a token that's within 30s of expiry is refreshed transparently.
6. "Log out" in the header calls `keycloak.logout()` which redirects through Keycloak's end-session endpoint and back to the SPA origin.

### Configuration overrides

The defaults are baked at Vite build time. To point at a different Keycloak,
set Vite env vars before `npm run build`:

```
VITE_KEYCLOAK_URL=https://kc.example.com
VITE_KEYCLOAK_REALM=my-realm
VITE_KEYCLOAK_CLIENT_ID=my-client
```

(The Docker `frontend` service inherits whatever was built into the image.
For multi-environment deploys, switch to a `/config.js` runtime-loaded file
generated by the container's nginx start hook; that's out of scope for this
POC.)

## Camunda REST endpoints used

All under `/engine-rest` (standard CIB seven / Camunda 7 REST API):

| Method + path | Used by |
|---|---|
| `GET  /process-definition?latestVersion=true` | `listProcessDefinitions` → ServicesPage, TasksPage, MyProcessesPage, IncidentsPage |
| `GET  /process-definition/key/{key}/xml` | `getProcessDefinitionXml` → TasksPage, IncidentsPage, CompletedProcessPage |
| `POST /process-definition/key/{key}/start` | `startProcess` → ServicesPage |
| `GET  /process-instance/count?…&active=true` | `countActiveProcessInstances` → TasksPage |
| `GET  /task?sortBy=…` | `listTasks` → TasksPage |
| `GET  /task?processInstanceId={id}` | `listTasksByInstance` → ServicesPage, MyProcessesPage |
| `GET  /task/{id}` | `getTask` → TaskDetailPage |
| `GET  /task/{id}/form-variables` | `getTaskVariables` → TaskDetailPage |
| `POST /task/{id}/complete` | `completeTask` → TaskDetailPage |
| `GET  /incident?…` | `listIncidents` → IncidentsPage, TasksPage |
| `PUT  /job/{id}/retries` | `setJobRetries` → IncidentsPage, TasksPage |
| `GET  /history/process-instance?startedBy={user}` | `listHistoricProcessInstancesByStarter` → MyProcessesPage |
| `GET  /history/process-instance/{id}` | `getHistoricProcessInstance` → CompletedProcessPage |
| `GET  /history/process-instance?…&finished=true` | `listFinishedProcessInstances` → (reserved) |
| `GET  /history/task?processInstanceId={id}` | `listHistoricTasks` → CompletedProcessPage |
| `GET  /history/task?processDefinitionId=…&taskDefinitionKey=…` | `listHistoricTasksByDefinition` → TasksPage |
| `GET  /history/variable-instance?processInstanceId={id}` | `listHistoricVariables` → CompletedProcessPage |
| `GET  /history/variable-instance?…&variableName=…` | `getHistoricVariable` → MyProcessesPage |

If you add an endpoint, add it both as a function in `camundaClient.ts` (with
JSDoc) and as a row in this table.

## How to add a new form

1. **BPMN** — add a `<bpmn:userTask>` with
   `camunda:formKey="react:<form-id>"` to the process file under
   `cib7/src/main/resources/processes/`. (Variables it reads/writes should
   be plain typed variables; see existing tasks for examples.)
2. **Component** — create `frontend/src/forms/<form-id>/<PascalCaseName>.tsx`
   implementing `FormProps`.
3. **Register** — add an entry to `formRegistry` in
   `frontend/src/forms/registry.ts`.
4. **Restart the backend** (in-memory H2 means a redeploy on startup picks up
   the new BPMN).
5. **Verify** — Services → start a process → walk through the new task.

There is no manifest validation. A `formKey` referencing a non-registered id
shows "No React form is registered for formKey …" on the TaskDetail page.

## Dev server, build, typecheck

```bash
cd frontend
npm install
npm run dev        # vite dev server on :5173, proxies /engine-rest → :8080
npm run build      # production build to dist/
npm run typecheck  # tsc --noEmit
```

`npm run typecheck` is the cheapest correctness check; run it after any
TypeScript change.

## Conventions

- **Style.** Google TypeScript Style Guide; match surrounding code on naming,
  imports, and formatting.
- **Components.** Function components only. Local state with `useState` /
  `useEffect` / `useCallback`. No external state library.
- **Errors surface as text.** Pages catch thrown errors and render them in a
  `<p className="form-error">`. Don't add toasts/modals for a POC.
- **No comments restating what code does.** The existing files use JSDoc on
  exported functions and types; keep that pattern. Add comments only where the
  *why* is non-obvious (e.g. the `localName` choice in `bpmn.ts`).
- **One folder per form.** Name the folder after the form id, the component in
  PascalCase. One component file per folder is fine until you actually need
  multiple.
