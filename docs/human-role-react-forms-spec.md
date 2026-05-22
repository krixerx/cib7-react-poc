# Human-Role React Forms — Design Spec

**Project:** BPA-backend / eRegistrations-style process platform
**Status:** Draft
**Last updated:** 2026-05-22
**Engine:** CIB seven (Camunda 7 fork), dynamic BPMN generation

---

## 1. Purpose

Define how **human roles** in BPMN processes are presented to users as
**React.js forms**. Human roles map to BPMN **user tasks**; each user task is
rendered by a hand-written React form component. This spec fixes the contract
between the process model (BPMN) and the frontend so the two can evolve
independently and safely.

---

## 2. Scope

**In scope**
- How a BPMN user task references its form.
- Where React forms live and how they are organised.
- The form component contract (props, registry, resolution).
- Edit vs read-only form modes.
- Data flow: prefill, submit/complete, read-only/history.
- The BFF (backend-for-frontend) task API surface.
- Deployment safety between dynamically generated BPMN and the frontend.

**Out of scope (for now)**
- Authentication / IdP integration details (assumed: groups map to candidate groups).
- The internal field-level component library API.
- Process design / role routing logic (DMN, gateways) — covered by existing builders.

---

## 3. Decision Log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Custom React tasklist app — **not** the CIB seven Tasklist webapp | Product-owned UX, branding, flow |
| D2 | **Forms as code** — hand-written React components, one per form | Team chose full control over forms |
| D3 | Each human role = one BPMN **`UserTask`** | Standard mapping |
| D4 | User task references its form via the native **`camunda:formKey`** attribute | First-class field; no plugin; clean REST exposure |
| D5 | `formKey` carries a **logical form ID**, not a path/URL | Decouples BPMN from frontend routing/bundle layout |
| D6 | **No version** in the key — breaking change mints a new form ID | Versioning code via `@n` means keeping dead components forever |
| D7 | Forms live in the **current frontend React project**, under `src/forms/` | Forms-as-code = forms are app code |
| D8 | Every form has **two modes**: editable (with buttons) + read-only | Stated requirement |
| D9 | Mode is chosen by the **frontend at runtime**, never encoded in BPMN | Edit/read-only is task state, not process structure |
| D10 | A **form manifest** validates `formKey` references at BPMN publish time | Catches dangling references before runtime |
| D11 | A **BFF** sits between React and the engine REST API | Auth, validation, variable shaping, history reads |

---

## 4. Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Process layer — CIB seven / BPMN                            │
│   One UserTask per human role                                │
│    ├─ camunda:formKey       → which React form               │
│    └─ camunda:candidateGroups → who may do it                │
└───────────────────────────┬──────────────────────────────────┘
                            │ Engine REST (/task, /history, …)
┌───────────────────────────▼──────────────────────────────────┐
│ BFF / Task API — backend gateway                              │
│   • list / claim / complete tasks for the caller's groups      │
│   • prefill form data from the `json` process variable         │
│   • merge submitted values + outcome back into `json`          │
│   • server-side validation + authorization                    │
│   • history reads for completed (read-only) tasks              │
└───────────────────────────┬──────────────────────────────────┘
                            │ REST / JSON
┌───────────────────────────▼──────────────────────────────────┐
│ React tasklist app (current frontend project)                 │
│   TaskListPage → TaskDetailPage → form (edit | view)           │
│   formRegistry resolves formKey → React component pair         │
└────────────────────────────────────────────────────────────────┘
```

**Layer responsibilities**

- **BPMN** — holds *only* a pointer (`formKey`) and assignment (`candidateGroups`).
  No form schema, layout, or field definitions.
- **BFF** — the only thing that talks to the engine. Enforces authz, validates
  submissions, shapes the `json` variable, reads history.
- **React app** — owns all UI: the task inbox, task detail, and the forms.

---

## 5. Process Layer — BPMN User Tasks

### 5.1 User task shape

```xml
<bpmn:userTask id="task_registration_approval"
               name="Registration approval"
               camunda:formKey="react:registration-approval"
               camunda:candidateGroups="approvers">
  <bpmn:extensionElements>
    <!-- create listener: dynamic candidate group assignment -->
    <camunda:taskListener event="create"
        class="org.unctad.eregistrations.camunda.listener.UserTaskCandidateGroupWithDeterminantsListener"/>
  </bpmn:extensionElements>
</bpmn:userTask>
```

- `camunda:formKey` — the form pointer (see §6).
- `camunda:candidateGroups` — who may claim/complete the task. May be assigned
  dynamically via the existing `create` task listener.
- **No form data lives in the BPMN.** The user task is mode-agnostic — it does
  not know about edit vs read-only.

### 5.2 The link mechanism is native, not a plugin

`formKey` is a built-in attribute of the Camunda BPMN extension namespace. There
is no plugin to install. The engine exposes it over REST
(`GET /task/{id}/form`), which the BFF reads.

### 5.3 CIB seven namespace note

CIB seven is a fork of Camunda 7. When generating BPMN for CIB seven, namespaces
and prefixes change:

| Camunda 7 | CIB seven |
|-----------|-----------|
| `xmlns:camunda="http://camunda.org/schema/1.0/bpmn"` | `xmlns:cib="http://cibseven.de/schema/1.0/bpmn"` |
| `camunda:formKey` | `cib:formKey` |
| `camunda:candidateGroups` | `cib:candidateGroups` |

The `formKey` *value grammar* (§6) is unaffected by the namespace.

---

## 6. Form Identification & Versioning

### 6.1 `formKey` grammar

```
react:<form-id>

  react      fixed prefix — marks this as a React-rendered form
  form-id    kebab-case logical identifier, unique across all forms
```

Examples: `react:registration-approval`, `react:document-upload`,
`react:company-details-review`.

### 6.2 Rules

- The `form-id` is **logical** — it is *not* a file path, route, or URL.
  React owns the mapping from `form-id` to a component (§8.3).
- The `form-id` must exist in the **form manifest** (§11) at BPMN publish time.
- A `formKey` whose `form-id` is unknown to the frontend renders a safe
  fallback (§9.4), never a crash.

### 6.3 Versioning — by new ID, not by `@version`

Forms are code. Pinning `@3` would mean keeping every historical form component
in the bundle forever.

- **Non-breaking change** (label tweak, new optional field, styling) — edit the
  component in place. The `form-id` stays the same.
- **Breaking change** (incompatible data shape, removed/renamed required field)
  — mint a **new `form-id`** (e.g. `registration-approval-v2`). Processes
  generated before the change keep pointing at the old component, which still
  exists; new processes use the new one.

---

## 7. Frontend Structure

Forms are part of the **current frontend React project**.

```
src/
  api/
    tasksClient.ts        # typed BFF client
  tasklist/
    TaskListPage.tsx      # inbox — tasks for the user's groups
    TaskDetailPage.tsx    # resolves formKey, picks mode, renders form
  forms/
    registry.ts           # formId → { edit, view }
    types.ts              # TaskMeta, EditFormProps, ViewFormProps
    form-manifest.json    # generated at build time from registry keys
    shared/               # reusable inputs, layout, validation hooks
    registration-approval/
      fields.tsx          # field components + field list — SHARED
      EditForm.tsx        # interactive layout + action buttons (default export)
      ReadOnlyForm.tsx    # static / summary layout (default export)
      validation.ts       # used by EditForm only
      index.test.tsx
    document-upload/
      fields.tsx
      EditForm.tsx
      ReadOnlyForm.tsx
    ...
```

**Rules**

- One folder per form.
- `fields.tsx` is the **single source of truth** for that form's field set
  (ids, labels, ordering, individual field components). Both `EditForm` and
  `ReadOnlyForm` import from it, so the two modes cannot drift apart.
- No form definitions are deployed with the BPMN. The BPMN carries only the
  `formKey` string.

---

## 8. The Form Contract

### 8.1 Shared types

```ts
// src/forms/types.ts

export interface TaskMeta {
  taskId: string;
  taskName: string;
  processInstanceId: string;
  processDefinitionKey: string;
  businessKey?: string;
  formId: string;                 // parsed from formKey
  assignee?: string;
  candidateGroups: string[];
  created: string;                // ISO
  completed?: string;             // ISO, set when task is done
}
```

### 8.2 Two prop contracts — one per mode

```ts
// Editable form: receives callbacks, can complete or save the task.
export interface EditFormProps {
  task: TaskMeta;
  data: Record<string, unknown>;                    // prefilled slice of `json`
  onSubmit: (values: object, outcome: string) => Promise<void>;
  onSaveDraft?: (values: object) => Promise<void>;
}

// Read-only form: data only. No callbacks — it structurally cannot mutate.
export interface ViewFormProps {
  task: TaskMeta;
  data: Record<string, unknown>;
}

export type EditForm = React.FC<EditFormProps>;
export type ViewForm = React.FC<ViewFormProps>;
```

Separate types (not a `readOnly` boolean) make an entire class of bugs
impossible: a read-only form can never be handed a submit handler.

### 8.3 The registry

```ts
// src/forms/registry.ts
import { lazy } from "react";
import type { EditForm, ViewForm } from "./types";

interface FormEntry {
  edit: EditForm;
  view: ViewForm;
}

export const formRegistry: Record<string, FormEntry> = {
  "registration-approval": {
    edit: lazy(() => import("./registration-approval/EditForm")),
    view: lazy(() => import("./registration-approval/ReadOnlyForm")),
  },
  "document-upload": {
    edit: lazy(() => import("./document-upload/EditForm")),
    view: lazy(() => import("./document-upload/ReadOnlyForm")),
  },
  // ...
};
```

- `lazy()` keeps each form out of the initial bundle until it is opened.
- The registry keys are the canonical list of valid `form-id`s and the source
  for the build-time manifest (§11).

---

## 9. Form Modes — Edit vs Read-Only

### 9.1 One form, two modes

Read-only is a **presentation mode** of the same form, not a separate form. The
`formKey` points at one `FormEntry`; the entry holds both modes.

### 9.2 Mode selection (frontend, at runtime)

| Situation | Mode |
|-----------|------|
| Task is active **and** assigned to the current user (has the role) | `edit` |
| Task is completed (history view) | `view` |
| Task active but viewed by a user without the role / not the assignee | `view` |
| Form shown in a process summary / audit context | `view` |

```tsx
// TaskDetailPage.tsx
const entry = formRegistry[task.formId];
if (!entry) return <UnknownFormFallback formId={task.formId} />;

const mode = canEdit(task, currentUser) ? "edit" : "view";
const Form = entry[mode];

return (
  <Suspense fallback={<Spinner />}>
    {mode === "edit"
      ? <Form task={task} data={data} onSubmit={complete} onSaveDraft={saveDraft} />
      : <Form task={task} data={data} />}
  </Suspense>
);
```

### 9.3 Buttons & outcomes (edit mode only)

- Action buttons live in `EditForm` (e.g. **Approve**, **Reject**,
  **Return for correction**).
- Each button is an **outcome** string passed to
  `onSubmit(values, outcome)`.
- The BFF merges `values` + `outcome` into the `json` process variable, then
  completes the task. A following exclusive gateway branches on the outcome
  via `S(json)...`.
- **Save draft** calls `onSaveDraft(values)` and does **not** complete the task.

### 9.4 Unknown / missing form

If `formRegistry[formId]` is undefined, render `UnknownFormFallback` — a clear
message, never a crash. This should be unreachable in practice because the
manifest check (§11) rejects such BPMN at publish time, but the guard stays.

---

## 10. Data Flow

The single process variable `json` (Camunda Spin) carries all process data.

### 10.1 Prefill (opening a task)

1. React opens a task → calls the BFF.
2. BFF reads the relevant **slice** of `json` for that form.
3. Slice is returned as `data` and passed to the form component.

### 10.2 Submit (completing a task)

1. User clicks an action button → `onSubmit(values, outcome)`.
2. BFF re-validates `values` server-side (never trust the client).
3. BFF merges `values` + `outcome` into `json`.
4. BFF completes the user task via the engine REST API.
5. Process continues to the next gateway / role.

### 10.3 Read-only / history

A `view`-mode form usually renders a **completed** task or instance. The data is
no longer in the live runtime, so the BFF reads it from process history
(`GET /history/variable-instance`) rather than `/task/{id}/form-variables`.
This read path must exist before any completed task is rendered.

---

## 11. Form Manifest & Deployment Safety

BPMN is generated and deployed **dynamically** (`BpmnAndFormPublish`); the
frontend deploys **separately**. A process that references a `formKey` the
deployed frontend does not have produces an unfillable task.

### 11.1 Form manifest

- A frontend build step generates `form-manifest.json` from the
  `formRegistry` keys:

  ```json
  { "forms": ["registration-approval", "document-upload", "company-details-review"],
    "builtAt": "2026-05-22T10:00:00Z" }
  ```

- The deployed frontend exposes this manifest (static file or endpoint).

### 11.2 Publish-time validation

`BpmnAndFormPublish` (or a validation step before deployment) must:

1. Fetch the current `form-manifest.json` from the deployed frontend.
2. Extract every `formKey` from every user task in the BPMN to be deployed.
3. **Reject the deployment** if any `form-id` is not in the manifest.

This turns a runtime failure into a publish-time error.

### 11.3 Deployment ordering rule

The frontend must be deployed **before** any BPMN that references its new forms.
The manifest check in §11.2 enforces this automatically — a BPMN referencing a
not-yet-deployed form simply fails to publish.

---

## 12. BFF / Task API (indicative surface)

| Method & path | Purpose |
|---------------|---------|
| `GET /tasks?scope=mine` | Inbox — active tasks for the caller's groups |
| `GET /tasks/{id}` | Task meta + `formKey` + prefilled `data` slice |
| `POST /tasks/{id}/complete` | Body `{ values, outcome }` — validate, merge, complete |
| `POST /tasks/{id}/draft` | Body `{ values }` — save draft, do not complete |
| `GET /tasks/{id}/history` | Read-only data for a completed task (from history) |

The BFF is the **only** component that calls the engine REST API. The browser
never talks to the engine directly.

---

## 13. Runtime Lifecycle (end-to-end)

```
1. Process reaches a UserTask.
   create listener assigns candidateGroups.
   userTask.formKey = "react:registration-approval"

2. React TaskListPage → GET /tasks?scope=mine → task appears in the user's inbox.

3. User opens the task → GET /tasks/{id}
   → BFF returns { task meta, formId: "registration-approval", data: {…} }

4. TaskDetailPage:
   entry = formRegistry["registration-approval"]
   mode  = canEdit(task, user) ? "edit" : "view"
   render entry[mode]

5a. EDIT: user fills the form, clicks "Approve"
    → onSubmit(values, "approve")
    → POST /tasks/{id}/complete  { values, outcome: "approve" }
    → BFF validates, merges into `json`, completes the task
    → gateway branches on outcome → process continues

5b. VIEW: completed task / no edit rights
    → BFF reads historic data, ReadOnlyForm renders it, no buttons
```

---

## 14. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Generated BPMN references a form the frontend lacks | Form manifest + publish-time validation (§11) |
| Edit and read-only layouts drift apart | Shared `fields.tsx` per form is the single source of truth (§7) |
| New/changed form requires a frontend release | Accepted cost of forms-as-code; keep the form set developer-owned |
| Completed task renders blank in `view` mode | BFF reads from process history, not live runtime (§10.3) |
| Concurrent human tasks (parallel gateway) overwrite each other's `json` writes | Forms write disjoint sections of `json`; BFF does field-level merge; `ParallelJsonMergeDelegate` handles the join |
| Client-side validation bypassed | BFF re-validates every submission server-side (§10.2) |
| Bundle grows as forms accumulate | `lazy()` per form; each form loads only when opened (§8.3) |

---

## 15. Open Questions

- **Authorization** — exact mapping of IdP groups → candidate groups →
  "can edit" decision (`canEdit`).
- **`json` slicing** — which sections of `json` each form may read and write,
  and how the BFF enforces it.
- **Draft storage** — are drafts stored as a process variable, in the task, or
  in a separate store?
- **Outcome variable** — is the button outcome merged into `json` or kept as a
  separate top-level process variable for gateway conditions?

---

## Appendix A — Naming conventions

- `formKey` value: `react:<kebab-case-id>`
- Form folder: `src/forms/<kebab-case-id>/`
- Components: `EditForm.tsx`, `ReadOnlyForm.tsx` (default exports)
- Shared field module: `fields.tsx`
- Breaking form change: new id with a `-vN` suffix
