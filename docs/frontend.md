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
| UI components | Own CSS design system + **MUI v5** (`@mui/x-data-grid`) for the Incidents grid only |
| Styling | Plain CSS on tokens: `src/styles/tokens.css` + one sheet per area in `src/styles/` |
| Icons | `lucide-react` |
| HTTP | `fetch` (no axios / SWR / React Query) |
| Auth | `keycloak-js` (OIDC PKCE against Keycloak) |

**Design system.** `src/styles/tokens.css` defines every colour for a light
and a dark scheme (`<html data-theme>`, set before first paint by
`public/theme-init.js` and toggled through `src/theme/colorScheme.ts`), the
category accents, fonts (Sora headings, Instrument Sans text, Red Hat Mono
case references, Noto Sans Arabic under `lang="ar"`) and radii.
`src/styles/base.css` holds the shared primitives (buttons, cards, pills,
glass surfaces, motion with a `prefers-reduced-motion` cut-off); the other
files in `src/styles/` style one area each. Generated forms keep the
service-builder class contract (`field`, `field-input`, `form-banner`,
`btn btn-primary`), so restyling never requires regenerating a form.
MUI v5 renders only the `pages/IncidentsPage.tsx` DataGrid, wrapped in a
`ThemeProvider` whose palette reads the resolved token values for the
active scheme (`src/theme/mui.ts`), so it follows a pack's brand colours
with no hex repeated. A service pack overrides the brand tokens (see
"Branding").

There are no state libraries; state is local React state.

## File layout

```
frontend/src/
├── main.tsx                       — bootstraps React + Router + AuthProvider, imports the stylesheets
├── App.tsx                        — layout shell, role-based nav + routes
├── styles/                        — tokens, base, shell, landing, cases, forms, backoffice, public
├── theme/
│   ├── colorScheme.ts             — light/dark scheme: resolve, toggle, useColorScheme()
│   └── mui.ts                     — MUI theme per scheme from the resolved tokens (Incidents DataGrid only)
├── vite-env.d.ts                  — Vite client types + VITE_KEYCLOAK_* env vars
├── auth/
│   ├── keycloak.ts                — keycloak-js singleton + ensureFreshToken()
│   └── AuthProvider.tsx           — login gate, useAuth() context (exposes role flags)
├── api/
│   ├── camundaClient.ts           — typed /engine-rest client + interfaces (attaches Bearer JWT)
│   ├── bpmn.ts                    — BPMN XML parsing: user tasks, activity names, flow graph + nextSteps()
│   ├── documentsApi.ts            — /api/documents client (upload-url, attachments, mine, download-url)
│   ├── paymentsApi.ts             — /api/public/payments client (pay page: status + checkout)
│   ├── paymentLinkApi.ts          — /api/cases/{id}/payment-link (pay link for the signed-in applicant)
│   ├── draftCaseApi.ts            — /api/cases/drafts + DELETE /api/cases/{id} (applicant's unsubmitted cases)
│   ├── mockBankApi.ts             — /api/public/mock-provider client (demo bank page)
│   ├── ownerConfirmationsApi.ts   — /api/public/consent/owner client (confirm page)
│   └── founderSignaturesApi.ts    — /api/public/consent/founder client (signing page)
├── services/
│   ├── categories.ts              — PartA life-event categories (the pack's catalog maps services to them)
│   └── CategoryIcon.tsx           — Lucide icon per category
├── components/
│   ├── OfficialBanner.tsx         — "official portal" strip + demo warning and Mailpit link
│   ├── SiteFooter.tsx             — shared footer (brand and demo note)
│   ├── PublicFrame.tsx            — banner + brand bar + footer for the email-link pages
│   ├── ThemeToggle.tsx            — light/dark switch
│   ├── LanguageSwitcher.tsx       — EN ⇄ AR switch
│   ├── CaseDetailLayout.tsx       — shared case-detail chrome (header + sticky Documents sidebar)
│   ├── ProcessTimeline.tsx        — "Case progress" card: milestone stepper + Full history + payment alert
│   ├── DocumentsCard.tsx          — submitted/generated documents list with presigned downloads
│   └── FileUpload.tsx             — drag-and-drop upload via /api/documents presigned PUT
├── pages/
│   ├── ServicesPage.tsx           — PartA route "/" (list of live services)
│   ├── MyProcessesPage.tsx        — PartA route "/my-processes" (action-first inbox)
│   ├── MyFilesPage.tsx            — PartA route "/my-files" (documents across all own cases)
│   ├── TasksPage.tsx              — PartB route "/" (two-pane worklist)
│   ├── IncidentsPage.tsx          — PartB route "/incidents" (cross-service overview)
│   ├── StatisticsPage.tsx         — route "/statistics" for the statistics-viewer role
│   ├── statisticsRange.ts         — date-range presets and duration formatting for it
│   ├── TaskDetailPage.tsx         — shared route "/tasks/:taskId" (thin route wrapper)
│   ├── TaskDetailView.tsx         — embeddable form host (route page + worklist right pane)
│   ├── CompletedProcessPage.tsx   — shared route "/processes/:processInstanceId" (thin route wrapper)
│   ├── ProcessHistoryView.tsx     — embeddable read-only history view (route page + worklist right pane)
│   ├── ConfirmOwnerPage.tsx       — public route "/confirm-owner/:token" (no Keycloak; token is the credential)
│   ├── SignFounderPage.tsx        — public route "/sign-founder/:token" (no Keycloak)
│   ├── PayPage.tsx                — public route "/pay/:token" (no Keycloak)
│   └── MockBankPage.tsx           — public route "/mock-bank/:sessionId" (demo payment provider)
└── forms/
    ├── types.ts                   — FormProps contract
    ├── registry.ts                — formId → React component, only for `Renderer: tsx` forms
    ├── resolve.ts                 — formId → TSX component or the schema renderer
    └── schema/                    — form definition v1 (definition.ts), inputs and checks
                                     (values.ts), fields (fields.tsx), renderer (SchemaForm.tsx)
```

One folder per form, named after the form id. The form component lives inside.

The `*View.tsx` files (`TaskDetailView`, `ProcessHistoryView`) hold the actual
load + render logic; their matching `*Page.tsx` files are thin route wrappers
that supply a Back button. This split is what lets the civil-servant worklist
embed the same view in its right pane while preserving the deep-link routes.

**`ProcessTimeline` (the "Case progress" card)** is embedded by both views —
above the form in `ProcessHistoryView`, below it in `TaskDetailView` — so
applicants and civil servants see the same answer to "where is this case and
how did it get here" without opening Cockpit:

- a horizontal **milestone stepper** (start, user tasks, the DMN
  auto-decision, wait states, end events) built from
  `listActivityInstances()`; completed steps show who/when, review steps get
  an **Approved / Sent back** chip derived from the `decision` variable's
  write history (`listVariableUpdates()`), the current wait pulses, and the
  expected remainder is walked forward through the BPMN sequence-flow graph
  (`parseFlowGraph()` + `nextSteps()` in `bpmn.ts` — one likely path,
  branching gateways resolved non-default-first);
- a collapsed **Full history** disclosure with every named activity,
  including the service-task machinery (emails, PDF generation, storage),
  consecutive repeats collapsed to one row with a ×N count;
- in both, a **kind icon** for who does the step: a person (`UserRound`,
  user tasks), the system (`Bot`, service, send, script and DMN tasks) or an
  outside party the case waits for (`Hourglass`, receive tasks). In the
  stepper the icon fills the dot and the dot's colour still shows the state;
  events keep the check mark or step number;
- a **payment-required alert** whenever the case is parked on
  `Task_WaitForPayment`; its button asks the backend for a pay link
  (`GET /api/cases/{id}/payment-link`, only for the user who started the
  case) and opens `/pay/{token}`.

## Routing

`App.tsx` reads `isCivilServant` from `useAuth()` and renders one of two
route sets. The TaskDetail and CompletedProcess pages are shared.

### PartA — applicant (`isCivilServant === false`)

| Path | Component | Purpose |
|---|---|---|
| `/` | `ServicesPage` | Pick a service and start a new instance |
| `/my-processes` | `MyProcessesPage` | The applicant's own instances + live status pill |
| `/my-files` | `MyFilesPage` | Every document of the applicant's own cases, certificates first |

### PartB — civil servant / back office (`isCivilServant === true`)

| Path | Component | Purpose |
|---|---|---|
| `/` | `TasksPage` | Two-pane worklist: filterable case list on the left, embedded form / history / incident block on the right |
| `/incidents` | `IncidentsPage` | Open engine incidents across all services; retry (the worklist also shows incidents inline; this page is the cross-service overview) |
| `/statistics` | `StatisticsPage` | Case indicators against the previous period, case flow, per-service comparison, back-office team figures and open failures. Follows the `statistics-viewer` realm role (`canViewStatistics`), not `isCivilServant`; all PartB users have it through their group. See [`statistics.md`](statistics.md). |

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

Landing page: a heading and one list of the live services, each with its
category icon, name, one-line summary and a start button. Nothing else sits
on the page, so the services are the first thing an applicant sees. The
summary comes from the service pack (`catalog:services.<processDefinitionKey>.summary`,
see "Service pack catalog and texts"); a service the pack does not list
shows none.

- `listProcessDefinitions()` populates the list; services are ordered by
  `categoryOf(s.key)` in `CATEGORIES` order (see `services/categories.ts`).
- `startService()`: anonymous → triggers `login()`; authenticated →
  `startProcess(key)`, then `listTasksByInstance(instanceId)` to find the
  first user task, then navigates to `/tasks/{taskId}`. If the engine has
  raced past the first user task (e.g. a service task in flight), navigates
  to `/my-processes` instead.
- Anonymous users can browse the catalog; sign-in is only required to start.

### `MyProcessesPage` (`src/pages/MyProcessesPage.tsx`) — PartA

- `listHistoricProcessInstancesByStarter(username)` returns every instance
  the applicant started (active + finished, newest first), plus one batched
  `listUnfinishedReceiveTasks()` call mapping each case to the receive task
  it is parked on (if any).
- For each active instance: `listTasksByInstance(id)` + `getHistoricVariable(id, 'sendBackReason')`
  + the wait-state map decide the status. The applicant's own task is
  detected by `assignee === username` (the BPMNs assign it to
  `${initiator}`), so the logic works for every service without hardcoded
  task ids:

  | Case state | `sendBackReason` | Status pill | Bucket |
  |---|---|---|---|
  | task assigned to me | empty / absent | **Awaiting submission** | Needs your attention |
  | task assigned to me | non-empty | **Sent back for corrections** | Needs your attention |
  | parked on `Task_WaitForPayment` | — | **Payment required** (card opens `/pay/{token}` via the payment-link endpoint) | Needs your attention |
  | parked on another receive task | — | **Waiting for signatures** (wait-state name in the meta line) | In progress |
  | back-office task open | — | **Under review** | In progress |
  | none (service task in flight) | — | **Processing** | In progress |

- **Drafts.** Opening a service starts the process instance at once, so an
  abandoned form would otherwise sit in the list for good. `GET
  /api/cases/drafts` names the applicant's cases with no completed task yet;
  their card reads "Draft, not submitted yet" and carries a **Delete**
  button with an inline confirmation. `DELETE /api/cases/{id}` removes the
  case from the engine runtime and history, so it leaves the list entirely.
  A sent-back case is never a draft, because its first task was completed
  once. If the drafts call fails the page renders without Delete buttons.
- Finished instances are labelled **Approved** if `endActivityId === 'EndEvent_Approved'`,
  otherwise **Ended**.
- Every row is clickable. When the applicant task is active, the row links to
  `/tasks/{taskId}` (editable form); a payment-required card fetches a pay
  link for the case and opens the public `/pay/{token}` page. Otherwise — finished OR in-flight
  with no applicant task — the row links to `/processes/{instanceId}`
  (read-only form + case-progress stepper), so an applicant can always see
  where the case is while the back office holds it.

### `MyFilesPage` (`src/pages/MyFilesPage.tsx`) — PartA

- One place to find certificates without opening each case. `GET
  /api/documents/mine` returns every document of every case the caller
  started, running or ended, newest first, each with its
  `processInstanceId`. The backend picks the cases from the engine's
  `startedBy` history for the token's user, never from an id the client
  sends, and reviewers get only cases they started themselves.
- Service names come from `listHistoricProcessInstancesByStarter` plus
  `listProcessDefinitions`; if either fails the rows still render, just
  without the service name.
- Filter chips: **Certificates and issued documents** (the `generated-*`
  categories, the default), **Uploaded by me** and **All files**, each with
  its count, plus a search over file name, category and service.
- Each row downloads through the same 60-second presigned URL as the
  case's Documents card and links to `/processes/{instanceId}`.

### `TasksPage` (`src/pages/TasksPage.tsx`) — PartB

Two-pane civil-servant worklist. Left: filterable case list. Right: the
selected case's detail (active form, read-only history, or incident block —
whichever applies). A button in the list header collapses the list to a
narrow rail (expand button, row count, vertical title) so the detail gets the
full width; the open form keeps its state, and the choice is remembered in
`localStorage` under `ereg-worklist-collapsed`.

- `listWorklist()` (in `camundaClient.ts`) loads the worklist in one call:
  joins `listRecentProcessInstances()` + `listIncidents()` +
  `listUnfinishedReceiveTasks()` (one batched call — fills `waitingOn` for
  cases parked on a receive task) + per-instance `firstName`/`lastName`
  history vars + per-active-instance current task. Returns one
  `WorklistRow` per case (denormalised, sorted by `startTime` desc). Rows
  without an open user task show the wait-state name inline, e.g.
  "⏳ Wait for state fee payment" — no Cockpit needed to see where a case
  is parked.
- **Filters** (all multi-select, empty = show all):
  - **Service** — process definition key
  - **Task** — current user-task `taskDefinitionKey`
  - **Status** — `pending` · `incident` · `confirmed` · `rejected`
  - **Applicant name** — substring match
  - **My cases** toggle — filters to `currentTask.assignee === username`
  - **Show drafts** checkbox, off by default — drafts (active, no user task
    completed yet, so the applicant has not submitted the first form) are
    hidden from the list and its count; a draft with an open incident is
    always shown. `listWorklist()` sets `isDraft` from
    `GET /history/task/count?finished=true`, the same rule the backend's
    `/api/cases/drafts` uses. Shown drafts carry a dashed "Draft" tag.
  - **Show payments** checkbox, off by default — cases parked on the fee
    payment receive task (`awaitingPayment`, any activity id matching
    `wait…payment`, today `Task_WaitForPayment`) are hidden the same way, since the
    applicant pays and the back office has nothing to do; one with an open
    incident is always shown.
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

### `StatisticsPage` (`src/pages/StatisticsPage.tsx`) — `statistics-viewer`

- Calls `GET /api/statistics` (`api/statisticsApi.ts`), never `/engine-rest`:
  the backend aggregates the engine history. It fetches the chosen period and
  the one of equal length before it (`previousRange`), so every indicator
  shows its change. Refreshes every 5 minutes.
- Layout: a dark hero band (`--banner-*` tokens, dark in both schemes) with
  the period buttons, service chips, task filter and a "needs attention"
  strip; then indicator tiles with sparklines (a tile picks the metric in the
  per-day chart), service mix, a case-flow Sankey, a service comparison table
  (a row focuses the page on that service), back-office team figures and the
  open failures.
- Charts are plain HTML/SVG coloured by the `--stat-*` and `--flow-*` tokens;
  every value is also in text (tiles, node labels, legends, tables, tooltips).

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

### Registry — `src/forms/registry.ts` and `resolve.ts`

`registry.ts` maps a form id to a TSX component only for a spec that says
`Renderer: tsx`; today it is empty. `resolve.ts` (`formFor`) returns that
component, or the schema renderer bound to the id, and `parseFormId` turns
`"react:owner-vehicle"` into `"owner-vehicle"`.

### Existing forms

All four are JSON definitions in `packs/reference/frontend/forms/`, generated
from their specs.

| Form id | Reads | Writes |
|---|---|---|
| `owner-vehicle` | `firstName`, `lastName`, `age`, `applicantEmail`, `objectId`, `additionalOwners`, `sendBackReason` (banner on re-submit) + vehicle list from `/api/public/registry/vehicles` + ID upload via `/api/documents` | names/age/email Strings + `objectId: String` (VIN), `additionalOwners: Json` (`[{name, email}]` only; the engine assigns party ids, confirmations and link tokens), `pendingIdDocument: Json`, `sendBackReason: ''` |
| `vehicle-review` | submitted data + registry values + `sendBackReason` (read-only) | **Accept:** `decision: 'approve'` / **Send back:** `decision: 'sendback'` + `sendBackReason: String` |
| `business-details` | OÜ founding details + AoA upload + co-founders | same contract shape as `owner-vehicle`, founder semantics |
| `review-business-registration` | submitted data (read-only) | same `decision` / `sendBackReason` contract as `vehicle-review` |

## Branding

The core look is the default; a service pack's `branding/` folder, served
at `/pack/branding/`, overrides parts of it. `src/pack/brand.ts` reads it
before the first render, together with the catalog:

| File | What it sets | Core default |
|---|---|---|
| `tokens.json` | brand colour tokens per scheme (`primary`, `primary-hover`, `primary-ink`, `primary-soft`, `primary-soft-border`, `mesh-1`..`mesh-4`, `banner-bg`, `banner-fg`, `banner-strong`) and the `display` and `body` font families | `src/styles/tokens.css` |
| `brand.json` | `logo` (`light`, optional `dark`) and `favicon`, image files beside it | the Landmark mark (`components/BrandMark.tsx`), no favicon |
| `locales/<lang>/brand.json` | `name`, `sub` (header and footer), `portal` (official banner); `name` is also the page title | `src/i18n/locales/<lang>/brand.json` |

Format v1 is strict: only those token names, hex colours (`#rrggbb`), font
family names, and image file names without a path. An invalid file is
refused whole and logged; the core default stays, so a broken pack never
blanks the page. Tokens become one `<style id="pack-theme">` after
`tokens.css`, with the same selectors. A brand font must be one the page
loads; today that is the Google Fonts link in `index.html` (Sora,
Instrument Sans), so loading pack font files is still open.
`src/pack/brand.test.ts` checks the reference pack, including WCAG AA
contrast of `primary` against `primary-ink` and the page surface in both
schemes. The colour category accents and status hues stay core.

## Service pack catalog and texts

The SPA holds no service-specific code or text. Before the first render,
`main.tsx` calls `loadPack()` (`src/pack/catalog.ts`), which reads
`/pack/catalog.json` (catalog format v1) and then every namespace it lists
in every language, `/pack/locales/<lang>/<namespace>.json`, into i18next:

- **`catalog`**: per service a summary for the services page and a fee name
  for the payment page, per issuer a name and subtitle.
- **`names`**: translations of the English display names the engine returns
  (process, task and activity names), keyed by the English name.
  `translateBackendName()` looks them up; an unknown name, such as free text
  from a civil servant, shows as it is.
- **one namespace per form** (see below).

`catalog.json` also says which category each service belongs to
(`categoryOf()`) and which issuer bills it, with the issuer's colour tone
(`issuerOf()`, the payment header's `pay-header-primary` / `pay-header-ok`).
The catalog is strict: an unknown key or value refuses it as a whole, and so
does a missing or unreadable file. The SPA then still starts, on core texts
only, and logs why. nginx serves `/pack/` with `Cache-Control: no-cache`, so
a pack update shows at the next page load. `src/pack/pack.test.ts` checks
the reference pack: both languages have the same keys, and every text a
form definition uses exists.

## Schema-driven forms

Forms are data, not code. A spec with `Renderer: schema` becomes a JSON form
definition (format v1) in the service pack,
`packs/reference/frontend/forms/<form-id>.json`. `forms/resolve.ts` gives
every form id without a TSX entry in `registry.ts` to
`forms/schema/SchemaForm.tsx`, which fetches `/pack/forms/<form-id>.json`,
checks it with `parseDefinition` (`forms/schema/definition.ts`) and draws it
with the usual classes (`form`, `summary`, `field`, `field-input`, `btn`,
`form-error`), so it looks like the generated TSX forms.

- **Elements:** intro texts (with a resubmission variant and a send-back
  banner), a read-only summary (plain values, a template over several
  variables, a coded value shown as text, or a list), fields of type
  `display`, `text`, `textarea`, `number`, `email` (text and email also as
  identity fields from the account), `select` (options from a backend
  registry), `file` (one upload through `FileUpload`), `contacts`
  (repeating name and email rows), `radio` (choices with hints) and `rows`
  (repeating rows of several columns, e.g. board members), notices, and actions that complete the task with
  fixed values or field input. An action that reveals fields (for example
  "Send back…" with a reason) works in two steps: show the fields, then
  confirm or cancel.
- **Texts** are i18n keys in the form's own namespace, shipped by the pack
  in `packs/reference/frontend/locales/<lang>/<namespace>.json`.
- **Strict:** an unknown key, element type or a reference to a missing field
  or action refuses the whole definition with "The form definition … is
  invalid", instead of drawing half a form.
- **Serving:** nginx serves the pack at `/pack/` (`location ^~ /pack/`, an
  honest 404 for a missing file); `npm run dev` does the same through the
  `serve-pack` plugin in `vite.config.ts`. A customer pack replaces the
  directory without rebuilding the SPA.
- **Fixed-length values** (`fixedLength`, the personal code): a monospaced
  underlay shows an underscore for every character still missing.
- **Checks** before completing (`values.ts`) look at every field at once:
  each field that needs attention gets the error border and its message under
  it (`field-invalid`, `field-message`, `aria-invalid`), a summary above the
  buttons lists all of them, and a field's mark goes away as soon as it is
  edited. The messages are the ones the TSX forms gave: required, integer ranges, email, the email required once
  contacts are listed, contact rows (name, email, repeats, not the own
  email). The engine checks the values again against the value schema.
- **All four forms are definitions:** `owner-vehicle`, `vehicle-review`,
  `business-details`, `review-business-registration`. `registry.ts` is empty
  and stays the escape hatch for a spec that says `Renderer: tsx`.

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

### Backend API clients (`documentsApi.ts`, `paymentsApi.ts`, `ownerConfirmationsApi.ts`, `founderSignaturesApi.ts`)

Thin typed clients for the business microservice's `/api/**` surface (same
origin, routed to `backend:8085` by Vite/nginx/Traefik). `documentsApi`
attaches the Keycloak Bearer (uploads stage via presigned PUT directly to
RustFS — the one browser call that leaves the SPA origin); the others are
deliberately unauthenticated: they serve the public token-link pages
(`/confirm-owner`, `/sign-founder`, `/pay`) and the vehicle dropdown, where
the engine-signed capability token in the URL is the credential. The SPA
treats the token as opaque and never creates one
([security rule 3](security.md#3-capability-links)). The pay page can only
start a checkout; the demo bank (`/mock-bank/:sessionId`) stands in for the
external provider, whose signed callback is what marks a fee paid
([rule 4](security.md#4-external-facts-need-proof)). `paymentLinkApi` is
the one Bearer-authenticated call here: it asks the backend for a pay link
for the signed-in applicant's own case.

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
| `GET  /history/activity-instance?processInstanceId={id}` | `listActivityInstances` → ProcessTimeline (case-progress stepper) |
| `GET  /history/activity-instance?unfinished=true&activityType=receiveTask` | `listUnfinishedReceiveTasks` → MyProcessesPage, TasksPage (wait-state labels, one batched call) |
| `GET  /history/detail?…&variableUpdates=true&variableName=…` | `listVariableUpdates` → ProcessTimeline (Approved / Sent back chips per review pass) |

If you add an endpoint, add it both as a function in `camundaClient.ts` (with
JSDoc) and as a row in this table.

## How to add a new form

1. **BPMN** — add a `<bpmn:userTask>` with
   `camunda:formKey="react:<form-id>"` to the process file under
   `packs/reference/engine/processes/`. (Variables it reads/writes should
   be plain typed variables; see existing tasks for examples.)
2. **Component** — create `frontend/src/forms/<form-id>/<PascalCaseName>.tsx`
   implementing `FormProps`.
3. **Register** — add an entry to `formRegistry` in
   `frontend/src/forms/registry.ts`.
4. **Restart the engine** (`ServiceDeployments` deploys the changed BPMN at
   startup; duplicate filtering leaves unchanged services alone).
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
