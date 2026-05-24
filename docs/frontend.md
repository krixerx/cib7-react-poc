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
7. [Camunda REST endpoints used](#camunda-rest-endpoints-used)
8. [How to add a new form](#how-to-add-a-new-form)
9. [Dev server, build, typecheck](#dev-server-build-typecheck)
10. [Conventions](#conventions)

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

There are no UI / form / state libraries. Forms are hand-written; state is local
React state. Keep it that way unless there is a concrete reason to add a
dependency.

## File layout

```
frontend/src/
├── main.tsx                       — bootstraps React + Router
├── App.tsx                        — layout shell, defines the three routes
├── styles.css
├── api/
│   ├── camundaClient.ts           — typed /engine-rest client + interfaces
│   ├── bpmn.ts                    — parseUserTasks(bpmnXml) → UserTaskDef[]
│   └── objectsApi.ts              — listPricedObjects() from restful-api.dev
├── pages/
│   ├── ServicesPage.tsx           — route "/"
│   ├── TasksPage.tsx              — route "/tasks"
│   └── TaskDetailPage.tsx         — route "/tasks/:taskId"
└── forms/
    ├── types.ts                   — FormProps contract
    ├── registry.ts                — formId → React component map
    ├── personal-details/PersonalDetailsForm.tsx
    └── review-application/ReviewApplicationForm.tsx
```

One folder per form, named after the form id. The form component lives inside.

## Routing

Defined in `App.tsx`:

| Path | Component | Purpose |
|---|---|---|
| `/` | `ServicesPage` | Lists deployed process definitions; start one |
| `/tasks` | `TasksPage` | Lists open user tasks grouped by service and task definition |
| `/tasks/:taskId` | `TaskDetailPage` | Renders one task's form |

There is no protected-route logic — no auth in this POC.

## Pages

Each page is described in terms of the client functions it calls — for the
underlying HTTP methods and paths, see the canonical
[endpoint table](#camunda-rest-endpoints-used).

### `ServicesPage` (`src/pages/ServicesPage.tsx`)

- Calls `listProcessDefinitions()` to populate the list.
- On Start: `startProcess(key)`, then `listTasksByInstance(instanceId)` to find
  the first user task, then navigates to `/tasks/{taskId}`. If the first step
  is not a user task, navigates to `/tasks` instead.

### `TasksPage` (`src/pages/TasksPage.tsx`)

- Loads `listProcessDefinitions()` and `listTasks()` in parallel.
- For each service, calls `getProcessDefinitionXml()` once and passes the XML
  through `parseUserTasks()` to extract the declared user-task ids/names. This
  is how we render every user task **even when no instance is sitting at it**.
- Tasks are grouped by `(processDefinitionId, taskDefinitionKey)`.

### `TaskDetailPage` (`src/pages/TaskDetailPage.tsx`)

- Loads `getTask(taskId)` and `getTaskVariables(taskId)` in parallel.
- Unwraps `{value, type}` variables to plain values (the `unwrap` helper).
- Resolves the form via `parseFormId(task.formKey)` → `formRegistry[formId]`.
- Renders the form with `task`, `data`, `onComplete`, `submitting` props.
- `onComplete` calls `completeTask(...)` and on success navigates back to
  `/tasks`.

## Forms

### Contract — `FormProps` (`src/forms/types.ts`)

```ts
export interface FormProps {
  task: CamundaTask;                                    // current task
  data: Record<string, unknown>;                        // unwrapped variables
  onComplete: (variables: CamundaVariables) => Promise<void>;
  submitting: boolean;
}
```

A form is a React component of type `ComponentType<FormProps>`. It is
responsible for:

1. Rendering inputs / read-only fields from `data`.
2. Local validation before submit.
3. Calling `onComplete(variables)` with the **CIB seven typed variable shape**:
   `{ <name>: { value, type: 'String' | 'Integer' | 'Long' | 'Double' | 'Boolean' } }`.
4. Disabling its submit controls while `submitting` is true.

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
| `personal-details` | `PersonalDetailsForm.tsx` | `firstName`, `lastName`, `age`, `objectId` (prefill if present) + product list from `restful-api.dev` | `firstName: String`, `lastName: String`, `age: Integer`, `objectId: String` |
| `review-application` | `ReviewApplicationForm.tsx` | `firstName`, `lastName`, `age`, `price` (read-only) | `decision: String` (`"approve"` or `"reject"`) |

## REST client (`api/`)

### `camundaClient.ts`

Thin typed wrapper around `fetch`. All calls go through `request<T>(path, init)`.

Exported types: `ProcessDefinition`, `CamundaTask`, `CamundaVariable`,
`CamundaVariables`, `CamundaVariableType`.

Exported functions: `listProcessDefinitions`, `getProcessDefinitionXml`,
`startProcess`, `listTasks`, `listTasksByInstance`, `getTask`,
`getTaskVariables`, `completeTask`.

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

## Camunda REST endpoints used

All under `/engine-rest` (standard CIB seven / Camunda 7 REST API):

| Method + path | Used by |
|---|---|
| `GET  /process-definition?latestVersion=true` | `listProcessDefinitions` → ServicesPage, TasksPage |
| `GET  /process-definition/key/{key}/xml` | `getProcessDefinitionXml` → TasksPage |
| `POST /process-definition/key/{key}/start` | `startProcess` → ServicesPage |
| `GET  /task?sortBy=…` | `listTasks` → TasksPage |
| `GET  /task?processInstanceId={id}` | `listTasksByInstance` → ServicesPage |
| `GET  /task/{id}` | `getTask` → TaskDetailPage |
| `GET  /task/{id}/form-variables` | `getTaskVariables` → TaskDetailPage |
| `POST /task/{id}/complete` | `completeTask` → TaskDetailPage |

If you add an endpoint, add it both as a function in `camundaClient.ts` (with
JSDoc) and as a row in this table.

## How to add a new form

1. **BPMN** — add a `<bpmn:userTask>` with
   `camunda:formKey="react:<form-id>"` to the process file under
   `backend/src/main/resources/processes/`. (Variables it reads/writes should
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
