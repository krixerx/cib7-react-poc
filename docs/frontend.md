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
├── services/
│   └── categories.ts              — PartA life-event categories + service-key → category mapping
├── pages/
│   ├── ServicesPage.tsx           — PartA route "/" (life-event catalog)
│   ├── MyProcessesPage.tsx        — PartA route "/my-processes"
│   ├── TasksPage.tsx              — PartB route "/" (two-pane worklist)
│   ├── IncidentsPage.tsx          — PartB route "/incidents" (cross-service overview)
│   ├── TaskDetailPage.tsx         — shared route "/tasks/:taskId" (thin route wrapper)
│   ├── TaskDetailView.tsx         — embeddable form host (route page + worklist right pane)
│   ├── CompletedProcessPage.tsx   — shared route "/processes/:processInstanceId" (thin route wrapper)
│   └── ProcessHistoryView.tsx     — embeddable read-only history view (route page + worklist right pane)
└── forms/
    ├── types.ts                   — FormProps contract
    ├── registry.ts                — formId → React component map
    ├── personal-details/PersonalDetailsForm.tsx
    ├── review-application/ReviewApplicationForm.tsx
    ├── business-details/BusinessDetailsForm.tsx
    └── review-business-registration/ReviewBusinessRegistrationForm.tsx
```

One folder per form, named after the form id. The form component lives inside.

The `*View.tsx` files (`TaskDetailView`, `ProcessHistoryView`) hold the actual
load + render logic; their matching `*Page.tsx` files are thin route wrappers
that supply a Back button. This split is what lets the civil-servant worklist
embed the same view in its right pane while preserving the deep-link routes.

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
| `/` | `TasksPage` | Two-pane worklist: filterable case list on the left, embedded form / history / incident block on the right |
| `/incidents` | `IncidentsPage` | Open engine incidents across all services; retry (the worklist also shows incidents inline; this page is the cross-service overview) |

### Shared

| Path | Component | Purpose |
|---|---|---|
| `/tasks/:taskId` | `TaskDetailPage` → `TaskDetailView` | Renders the React form for one task; completing it returns the user to their list (`/` for civil servants, `/my-processes` for applicants). Deep-link route; the civil-servant worklist embeds the same `TaskDetailView` in its right pane. |
| `/processes/:processInstanceId` | `CompletedProcessPage` → `ProcessHistoryView` | Read-only view of a process instance (ended OR in-flight) — last completed user task's form pre-filled with historic variables. Deep-link route; the civil-servant worklist embeds the same `ProcessHistoryView` in its right pane when the selected case has no active user task. |

A catch-all `*` route redirects to `/` so the role-appropriate landing page
always wins after a logout/login. There is no per-route role check beyond
which routes are rendered — the engine's authorization filter is the real
gate.

## Pages

Each page is described in terms of the client functions it calls — for the
underlying HTTP methods and paths, see the canonical
[endpoint table](#camunda-rest-endpoints-used).

### `ServicesPage` (`src/pages/ServicesPage.tsx`) — PartA

Life-event catalog: a hero strip + 3×2 grid of category tiles (Business,
Family & Civil Status, Property & Land, Travel & Identity, Social & Health,
Other). Inspired by portals like monentreprise.bj and lesotho.eregulations.org
— citizens pick a topic before drilling into a specific service.

- `listProcessDefinitions()` to populate the catalog; deployed services are
  bucketed by `categoryOf(s.key)` (see `services/categories.ts`).
- Tiles with zero services show **Coming soon** and are disabled.
- Tiles with exactly one service skip the inline list and call `startService()`
  on click — saves the user the "click twice for the same thing" UX hit.
- Tiles with two or more services open an inline panel below the grid, with
  `scrollIntoView({ behavior: 'smooth' })` so the panel is obvious. Picking a
  service from there is the same `startService()` path.
- `startService()`: anonymous → triggers `login()`; authenticated →
  `startProcess(key)`, then `listTasksByInstance(instanceId)` to find the
  first user task, then navigates to `/tasks/{taskId}`. If the engine has
  raced past the first user task (e.g. a service task in flight), navigates
  to `/my-processes` instead.
- Anonymous users can browse the catalog; sign-in is only required to start.

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
- Every row is clickable. When the applicant task is active, the row links to
  `/tasks/{taskId}` (editable form). Otherwise — finished OR in-flight with no
  applicant task — the row links to `/processes/{instanceId}` (read-only form
  with the data the applicant submitted), so an applicant can always go back
  and see what they sent while the back office holds the case.

### `TasksPage` (`src/pages/TasksPage.tsx`) — PartB

Two-pane civil-servant worklist. Left: filterable case list. Right: the
selected case's detail (active form, read-only history, or incident block —
whichever applies).

- `listWorklist()` (in `camundaClient.ts`) loads the worklist in one call:
  joins `listRecentProcessInstances()` + `listIncidents()` + per-instance
  `firstName`/`lastName` history vars + per-active-instance current task.
  Returns one `WorklistRow` per case (denormalised, sorted by `startTime`
  desc).
- **Filters** (all multi-select, empty = show all):
  - **Service** — process definition key
  - **Task** — current user-task `taskDefinitionKey`
  - **Status** — `pending` · `incident` · `confirmed` · `rejected`
  - **Applicant name** — substring match
  - **My cases** toggle — filters to `currentTask.assignee === username`
- **Status** is computed in `statusFor()` from the instance state:
  - active + ≥1 open incident → `incident` (row gets soft red wash)
  - active + no incidents → `pending`
  - ended at an end event whose id matches `/reject/i` → `rejected`
  - ended at any other end event → `confirmed` (default-confirmed because
    today's BPMNs have no terminal "rejected" end event — see the function's
    own JSDoc for the reasoning)
- Selection lives in `?case=<processInstanceId>`. Right pane renders one of:
  - `IncidentBlock` if the selected row has open incidents (Retry buttons
    call `setJobRetries(incident.configuration, 1)`)
  - `TaskDetailView` if the row has an active user task (editable form)
  - `ProcessHistoryView` otherwise (read-only form populated from history)
- After a successful `completeTask`, `clearCase()` + `load()` refresh the
  list. `cache: 'no-store'` on every `/engine-rest` fetch keeps the post-
  complete refetch from serving the browser's stale cached response.
- List header shows a **↻** refresh button + "Refreshing…" status during a
  background fetch (`aria-live="polite"`).

### `IncidentsPage` (`src/pages/IncidentsPage.tsx`) — PartB

- Cross-service overview. Lists open incidents via `listIncidents()`. For
  `failedJob` incidents, surfaces a **Retry** button that calls
  `setJobRetries(incident.configuration, 1)` so the job executor picks the
  job up again. The worklist also folds incidents into each case's row —
  this page is the flat "what's stuck across the whole engine" view.

### `TaskDetailView` + `TaskDetailPage` — shared

`TaskDetailView` is the reusable form host. `TaskDetailPage` is a thin route
wrapper around it.

- Loads `getTask(taskId)` and `getTaskVariables(taskId)` in parallel.
- Unwraps `{value, type}` variables to plain values.
- Resolves the form via `parseFormId(task.formKey)` → `formRegistry[formId]`.
- Renders the form with `task`, `data`, `onComplete`, `submitting`, `readOnly` props.
- `onComplete` calls `completeTask(...)` and fires the `onCompleted` callback;
  the route page navigates back to the role's list, the worklist clears
  selection and refetches.
- `topSlot` prop lets the host inject a Back/Close button into the card head.

### `ProcessHistoryView` + `CompletedProcessPage` — shared

`ProcessHistoryView` is the reusable read-only view. `CompletedProcessPage`
is a thin route wrapper.

- Works for both ended and in-flight instances. Loads
  `getHistoricProcessInstance`, `listHistoricTasks`, `listHistoricVariables`,
  and `getProcessDefinitionXml` in parallel.
- Renders the LAST completed user task's form with `readOnly` set, populated
  from historic variables — that's the most recent snapshot of the case from
  the user's perspective.
- Header line adapts: `Submitted {date} · Currently with {step}` for in-flight,
  `Completed {date} · {outcome}` for ended.
- Same `topSlot` pattern as `TaskDetailView`.

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
`HistoricProcessInstance`, `HistoricTask`, `HistoricVariableInstance`,
`WorklistRow`.

Exported functions: `listProcessDefinitions`, `getProcessDefinitionXml`,
`startProcess`, `listTasks`, `listTasksByInstance`, `getTask`,
`getTaskVariables`, `completeTask`, `listIncidents`,
`countActiveProcessInstances`, `setJobRetries`,
`listFinishedProcessInstances`, `listHistoricProcessInstancesByStarter`,
`getHistoricProcessInstance`, `listHistoricTasks`,
`listHistoricTasksByDefinition`, `listHistoricVariables`,
`getHistoricVariable`, `listRecentProcessInstances`, `listWorklist`.

Conventions:

- Same-origin path: `const BASE = '/engine-rest'`.
- 204 No Content is treated as `undefined`.
- Non-2xx throws `Error` with `status + body`. Pages catch and render the message.
- `cache: 'no-store'` on every fetch — engine GETs don't set
  Cache-Control: no-store, and the civil-servant worklist refetches
  immediately after a task completes; without this, the browser would serve
  the just-completed task as still pending until a full page reload.

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
| `GET  /process-definition?latestVersion=true` | `listProcessDefinitions` → ServicesPage, TasksPage (via `listWorklist`), MyProcessesPage, IncidentsPage |
| `GET  /process-definition/key/{key}/xml` | `getProcessDefinitionXml` → IncidentsPage, ProcessHistoryView |
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
| `GET  /history/process-instance?sortBy=startTime&sortOrder=desc` | `listRecentProcessInstances` → TasksPage (via `listWorklist`) |
| `GET  /history/process-instance/{id}` | `getHistoricProcessInstance` → ProcessHistoryView |
| `GET  /history/process-instance?…&finished=true` | `listFinishedProcessInstances` → (reserved) |
| `GET  /history/task?processInstanceId={id}` | `listHistoricTasks` → ProcessHistoryView |
| `GET  /history/task?processDefinitionId=…&taskDefinitionKey=…` | `listHistoricTasksByDefinition` → (reserved; old tree view used it) |
| `GET  /history/variable-instance?processInstanceId={id}` | `listHistoricVariables` → ProcessHistoryView |
| `GET  /history/variable-instance?…&variableName=…` | `getHistoricVariable` → MyProcessesPage, TasksPage (via `listWorklist`) |

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
