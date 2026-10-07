---
name: service-builder
description: |
  Generate or modify a CIB seven business service from its markdown spec under
  docs/business/services/<service>/ of a service pack (the repository root of a
  pack repository; packs/test/, the core test pack, in the core repository). Reads README.md, forms/*.md, service-tasks/*.md,
  and decisions/*.md; emits BPMN, DMN, FreeMarker payload templates, React form
  components, registry entries, MCP manifest + LLM training markdown for the
  /mcp microservice, and a regenerated mermaid diagram. Use when asked to "build
  the service", "generate from the spec", "scaffold a new service", "regenerate
  the BPMN", "regenerate the MCP manifest", or after editing any file under
  a pack's docs/business/services/.
allowed-tools:
  - Read
  - Write
  - Edit
  - Glob
  - Grep
  - Bash
  - AskUserQuestion
---

# /service-builder — spec-first CIB seven service generator

This skill turns a service's markdown spec into running code. The analyst owns
the markdown under `<pack>/docs/business/services/<service>/`; everything else is
generated. The goal is that **regenerating the same spec produces the same
output**, so modifications work by editing the spec and re-running.

## Where you run

The skill writes into a **service pack**, written `<pack>` below:

- **In a pack repository** (the usual place; a vendored copy of this skill,
  `pack.yaml` at the repository root): see below.
- **In the core repository** (this skill at `.claude/skills/service-builder/`
  beside `packs/test/`): `<pack>` is `packs/test`, the core test pack, a
  frozen copy of the reference services that the core's tests run on. Change
  it only when a core change needs it to. `<tools>` is `scripts/` (run
  `npm ci` there once). Check the result with `scripts/pack-check.sh` and the
  core's test suites.
- **In a pack repository** (a vendored copy of this skill, `pack.yaml` at the
  repository root): `<pack>` is the repository root, and `<tools>` is
  `.claude/skills/service-builder/tools/` (run `npm ci` there once). The
  core's code is not in the repository, so a `Renderer: tsx` form, a new
  shared value rule or anything else under "do not edit" below is a core
  change: stop and ask. Check the result with the core's
  `scripts/pack-check.sh <pack-dir>` from a checkout of the core version in
  `VERSION` (the pack repository's CI does that).

Paths below without `<pack>/` are the core repository's. The examples below
link to the core test pack's copy of the reference services; a vendored copy
links them to the reference pack's repository,
[krixerx/eregistrations-reference-pack](https://github.com/krixerx/eregistrations-reference-pack)
(`scripts/package-service-builder.mjs`).

Two reference services, both complete specs (README, `forms/`,
`service-tasks/`, `decisions/`, `data/` or `consent.md`, `build/`); read
both before generating a new service:

- **`vehicle-registration`** — a registry lookup, a DMN, co-owner
  signatures (multi-instance subprocess), a reminder timer, a state fee
  with payment, two PDFs.
- **`business-registration`** — the same shape with a file upload,
  co-founder signatures and a different issuing authority.

| | vehicle-registration | business-registration |
|---|---|---|
| Spec | [`README.md`](../../../packs/test/docs/business/services/vehicle-registration/README.md) | [`README.md`](../../../packs/test/docs/business/services/business-registration/README.md) |
| BPMN | [`vehicle-registration.bpmn`](../../../packs/test/engine/processes/vehicle-registration/vehicle-registration.bpmn) | [`business-registration.bpmn`](../../../packs/test/engine/processes/business-registration/business-registration.bpmn) |
| DMN | [`vehicle-auto-approval.dmn`](../../../packs/test/engine/processes/vehicle-registration/vehicle-auto-approval.dmn) | [`business-auto-approval.dmn`](../../../packs/test/engine/processes/business-registration/business-auto-approval.dmn) |
| Forms | [`owner-vehicle.json`](../../../packs/test/frontend/forms/owner-vehicle.json), [`vehicle-review.json`](../../../packs/test/frontend/forms/vehicle-review.json) | [`business-details.json`](../../../packs/test/frontend/forms/business-details.json), [`review-business-registration.json`](../../../packs/test/frontend/forms/review-business-registration.json) |
| Service tasks | [`service-tasks/`](../../../packs/test/docs/business/services/vehicle-registration/service-tasks/) | [`service-tasks/`](../../../packs/test/docs/business/services/business-registration/service-tasks/) |
| MCP manifest | [`build/mcp-service.json`](../../../packs/test/docs/business/services/vehicle-registration/build/mcp-service.json) | [`build/mcp-service.json`](../../../packs/test/docs/business/services/business-registration/build/mcp-service.json) |
| MCP training | [`build/mcp-training.md`](../../../packs/test/docs/business/services/vehicle-registration/build/mcp-training.md) | [`build/mcp-training.md`](../../../packs/test/docs/business/services/business-registration/build/mcp-training.md) |

Cross-service artifacts:

- Form registry: [`frontend/src/forms/registry.ts`](../../../frontend/src/forms/registry.ts) (full rewrite per run)
- Aggregated MCP index: [`<pack>/docs/business/services/build/services.json`](../../../packs/test/docs/business/services/build/services.json)
- Mermaid generator: [`scripts/bpmn-to-mermaid.mjs`](../../../scripts/bpmn-to-mermaid.mjs)

The top-level [`README.md` § "Add or modify a service"](../../../README.md#add-or-modify-a-service)
explains the human workflow around this skill.

---

## 1. Inputs — the spec contract

A service folder looks like:

```
<pack>/docs/business/services/<service>/
├── README.md                  required — flow, roles, variables, trade-offs
├── forms/
│   └── <form-id>.md           one per user task
├── service-tasks/
│   └── <task-id>.md           one per integration / service task
└── decisions/
    └── <decision-id>.md       one per DMN (optional)
```

Templates for each file live under [`spec-template/`](spec-template/) — copy
them to seed a new service. The required fields each file must define are
documented inside the templates. If a spec is missing a required field, **stop
and ask** rather than guessing.

---

## 2. Outputs — files the skill writes

| Source file | Generated file(s) |
|---|---|
| `<service>/README.md` (flow section) | `<pack>/engine/processes/<service>/<service>.bpmn` |
| `<service>/decisions/<id>.md` | `<pack>/engine/processes/<service>/<id>.dmn` |
| `<service>/service-tasks/<id>.md` with payload-template body | `<pack>/engine/templates/<id>.json.ftl` |
| `<service>/forms/<id>.md` with `Renderer: schema` (the default) | `<pack>/frontend/forms/<id>.json` (form definition v1; § 8.0) and its texts `<pack>/frontend/locales/{en,ar}/<namespace>.json` |
| Every `<service>/README.md` § Catalog, plus every form's namespace | `<pack>/frontend/catalog.json` and `<pack>/frontend/locales/{en,ar}/catalog.json` (catalog format v1; § 8.1) |
| Every `name=` in every generated BPMN | `<pack>/frontend/locales/{en,ar}/names.json` (display names; § 8.1) |
| `<service>/forms/<id>.md` with `Renderer: tsx` (escape hatch only; core repository only) | `frontend/src/forms/<id>/<PascalCase>Form.tsx` (§ 8) |
| Every `Renderer: tsx` form (core repository only) | `frontend/src/forms/registry.ts` (full rewrite, alphabetical by id) |
| `<service>.bpmn` after regeneration | mermaid block inside `<service>/README.md` |
| `<service>/README.md` (variables + forms) + `<service>/forms/*.md` | `<service>/build/mcp-service.json` (MCP manifest: texts and offered fields; § 11) |
| `<service>/README.md` + form audiences | `<service>/build/mcp-training.md` (LLM training markdown; § 11) |
| Every `<service>/build/mcp-service.json` across every service | `<pack>/docs/business/services/build/services.json` (aggregated MCP index; § 11) |
| `<service>/forms/*.md` (Actions `complete-with`) + `<service>/README.md` (§ Variable write policy) | `<pack>/engine/processes/<service>/variable-policy.json` (client-writable variables per start and per form; docs/security.md rule 2; plus `identity`: variable to `givenName`, `familyName` or `email` from the README's **Identity** line) |
| `<service>/consent.md` | `<pack>/backend/consent/<purpose>.yaml` (co-signing descriptor; step 10c) |
| Every `<service>/README.md` § Documents | `<pack>/backend/documents.json` (`platform: 2`, `categories: { <name>: { by: applicant \| system } }`, without the core's `generated-certificate`; read by the backend's `DocumentCategories`) and the labels as `documents.<category>` in `<pack>/frontend/locales/{en,ar}/catalog.json` |
| `<service>/README.md` § State fee | `<pack>/backend/payment/<service>.yaml`: `platform: 2`, `process`, `service` (fee name), `recipient`, `currency`, and `amount: { flat: N }` or `amount: { tiers: { variable, below: [{limit, amount}, ...] ascending, otherwise } }`; read by the backend's `FeeCatalog` |
| `<service>/data/<entity>.md` | `<pack>/backend/registry/<entity>.yaml` (descriptor) and `<pack>/backend/db/registry/V<n>__<entity>.sql` (table + seed; step 10b) |
| `<service>/forms/<id>.md` (Fields `Validation`, Conditional rules) | `<pack>/engine/processes/<service>/schemas/<id>.json` and `schemas/start.json` (value rules the engine enforces on every client; step 10a) |

The three `build/`-typed outputs above are the contract with the `mcp/` Node
sidecar — the pack's `docker/mcp.Dockerfile` layer copies
`<pack>/docs/business/services/` into the image and the loader walks every `<service>/build/mcp-service.json + mcp-training.md`
pair. See [`mcp/src/services/manifest.ts`](../../../mcp/src/services/manifest.ts)
for the consumer side.

**Deployment convention:** each service's BPMN + DMN files go into their own
`<pack>/engine/processes/<service>/` folder (folder name = spec
folder name). `ServiceDeployments.java` turns every folder into ONE named
engine deployment at startup — that's what makes per-service versioning,
per-service rollback, and `decisionRefBinding="deployment"` work. Never emit
resources into the flat `processes/` root, and never put one service's DMN
into another service's folder.

Everything outside this list is hand-written platform code — don't touch it.
In particular: **do not edit** anything under `cib7/src/main/java/`,
`frontend/src/api/`, `frontend/src/pages/`, `keycloak/`, `pdf-renderer/`,
`mcp/src/`, `docker-compose.yml`, or `application.yaml` from this skill.

---

## 3. Workflow

Run these in order. For modifications, the algorithm is the same — the
generated files get rewritten in place; idempotent runs are a no-op.

1. **Locate the service.** If invoked with a service name, use it. Otherwise
   ask: "Which service?" with one option per folder under
   `<pack>/docs/business/services/`.
2. **Read every spec file** in the service folder. Build an in-memory model:
   process id (camelCase = folder name in kebab transformed; usually written
   explicitly in the README), start event, nodes (user tasks, service tasks,
   business rule tasks, gateways, boundary events, end events), sequence
   flows with conditions and defaults, process variables, FreeMarker payload
   templates.
3. **Validate** against [§ 4](#4-validation-rules). On failure, list every
   violation in one message and stop — don't generate partial output.
4. **Emit BPMN** at `<pack>/engine/processes/<service>/<service>.bpmn` using
   the patterns in [§ 5](#5-bpmn-authoring-patterns). Include BPMNDI layout
   bounds so Cockpit can render the diagram; pack them on a horizontal
   waterline (y=160, x advances by 140 per task) and let the modeller adjust
   later if needed. Don't try to be clever — readable lanes beat dense lanes.
5. **Emit DMN** for each `decisions/<id>.md` at
   `<pack>/engine/processes/<service>/<id>.dmn` using the pattern in
   [§ 6](#6-dmn-authoring-patterns). Every DMN **must** carry
   `camunda:historyTimeToLive` matching the BPMN's TTL.
6. **Emit FreeMarker payloads** for each `service-tasks/<id>.md` that
   declares a `payload-template:` body — write
   `<pack>/engine/templates/<id>.json.ftl`. Inline payloads
   (short, no template marker) go inside the BPMN as `<camunda:inputParameter
   name="payload">…</camunda:inputParameter>` instead.
7. **Emit the forms.** A spec with `Renderer: schema` (the default) becomes a
   JSON definition at `<pack>/frontend/forms/<id>.json` following
   [§ 8.0](#80-form-definition-v1); no React code is written for it. Only a
   spec that says `Renderer: tsx` gets a React component at
   `frontend/src/forms/<id>/<PascalCase>Form.tsx`, following
   [§ 8](#8-react-form-template). Always handle the `readOnly` prop, the
   `data` defaults, the typed `onComplete` variables, and (for forms on the
   send-back loop) the `sendBackReason` banner pattern.
8. **Rewrite the registry.** Scan every `forms/<id>.md` across every
   service. Rewrite `frontend/src/forms/registry.ts` end-to-end with one
   import per form id (alphabetical) and matching entries. Don't try to do
   line-level edits — a clean rewrite beats merge conflicts.
9. **Emit the MCP service manifest.** Write
   `<service>/build/mcp-service.json` following the schema in
   [§ 11.1](#111-mcp-servicejson): the texts and, per form, the offered
   fields with their descriptions (from the Fields table's meaning column).
   The value rules are not repeated; the sidecar takes them from the form
   schemas step 10 writes.
10. **Emit the variable write policy** at
    `<pack>/engine/processes/<service>/variable-policy.json`:
    ```json
    {
      "$comment": "Generated from <pack>/docs/business/services/<service>. Do not hand-edit.",
      "processDefinitionKey": "<process id>",
      "start": ["<names>"],
      "forms": { "<form-id>": ["<names>"] }
    }
    ```
    Derivation rule: a form's list is the union of the variable names in
    every `complete-with` cell of its `forms/<id>.md` Actions table, plus the
    `writeTo` variable of each required document; `start` is the property
    list of the MCP start `variables` schema (step 9). Then apply the
    README's "Variable write policy" section, which is the source of truth
    for the result and lists any SPA-only fields. Never list a config bean
    name (`ReservedBeansPlugin.RESERVED_NAMES`), `initiator`, a DMN output, a
    connector output, consent or payment state, or a decision on an
    applicant form or the start. Identity fields may appear on the applicant
    form only when `IdentityFieldRegistry` binds them for this process (the
    listener rejects a changed value). `VariableWritePolicyFilter` enforces
    the file; a service without one accepts no client variables at all.
10a. **Emit the value schemas** at
    `<pack>/engine/processes/<service>/schemas/<form-id>.json`, one
    per form whose Fields table has a writable field, plus `schemas/start.json`
    when the policy's `start` list is non-empty. Each is a JSON Schema
    2020-12 object over the submitted variable values (a `Json` variable as
    its parsed value):
    ```json
    {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "$comment": "Generated from <pack>/docs/business/services/<service>/forms/<id>.md. Do not hand-edit.",
      "x-process": "<process id>",
      "x-form": "<form-id>",
      "type": "object",
      "properties": { "<name>": { } },
      "required": ["<name>"]
    }
    ```
    Map each writable field's `Validation` cell with this vocabulary and
    nothing else (an unmapped phrase is a spec gap: stop and ask):

    | Validation phrase | Schema |
    |---|---|
    | `non-empty` | `{"$ref": "<core>#/$defs/nonBlank"}` |
    | `non-empty, max N chars` | `nonBlank` plus `"maxLength": N` in an `allOf` |
    | `integer A..B` | `{"type": "integer", "minimum": A, "maximum": B}` |
    | `number >= N` | `{"type": "number", "minimum": N}` |
    | `one of a, b, c` | `{"enum": ["a", "b", "c"]}` |
    | `email` | `{"$ref": "<core>#/$defs/email"}` |
    | `email or empty` | `{"$ref": "<core>#/$defs/emailOrEmpty"}` |
    | `personal code (EE)` | `{"$ref": "<core>#/$defs/personalCodeEE"}` |
    | `vehicle VIN` | `{"$ref": "<core>#/$defs/vin"}` |
    | `list of contacts` | `{"type": "array", "items": {"$ref": "<core>#/$defs/contact"}}` |
    | `list of {f1, f2}, min N` | array of objects with exactly those properties (all required, `additionalProperties: false`), `minItems: N`, each property per its own phrase |
    | `pending upload or null` | `{"$ref": "<core>#/$defs/pendingUploadOrNull"}` |
    | `cleared to ""` | `{"const": ""}` |
    | `identity` | leave out: `IdentityValidationListener` checks it against Keycloak |

    `<core>` is `https://companylab.ai/schemas/core/v1.json`, the shared
    definitions in `cib7/src/main/resources/schemas/core-v1.json` (platform
    code, never emitted by this skill). Fields with Required `yes` go into
    `required`; `no (form: yes)` means the form insists but another client
    (the MCP agent) does not send it yet, so it stays out of `required`. Each row of the form's "Conditional rules" section becomes an
    `if`/`then` (collect several in an `allOf`). `start.json` uses
    `"x-form": "start"`, the same property rules as the applicant form for
    the start variables, and no `required` (the SPA starts with no
    variables). `VariableWritePolicyFilter` enforces the files through
    `FormSchemaRegistry` and answers 400 with the failed rules.
10b. **Emit the registries** for every `<service>/data/<entity>.md`. The
    backend's registry module serves them; never write Java for a registry.
    Descriptor at `<pack>/backend/registry/<entity>.yaml`:
    ```yaml
    # Generated from <pack>/docs/business/services/<service>/data/<entity>.md. Do not hand-edit.
    platform: 2
    entity: <entity>            # [a-z][a-z0-9-]*, the URL segment
    table: reg_<name>           # must start with reg_
    key: <field>
    sort: <field>               # optional, "List order"; default the key
    fields:
      <field>: { type: string|integer|number|boolean, maxLength: N }
    derived:                    # optional
      <field>: { yearsSince: <integer field> }
    operations:
      list: { access: public|internal }
      lookup: { access: public|internal }
    ```
    Field names are camelCase; the column is their snake_case
    (`fuelType` → `fuel_type`). `public` is only for harmless reference data
    (docs/security.md rule 5); anything about a person is `internal` at most.
    An access level, type, operation or derived rule outside this list is a
    spec gap: stop and ask. `RegistryCatalog` refuses to start on it anyway.

    Migration at `<pack>/backend/db/registry/V<n>__<entity>.sql`,
    the next free version across the folder. The first one creates the table
    and inserts the Seed rows; every later spec change is a new file that
    alters what is there, never an edit of an applied one. Quote every
    identifier as lowercase (`"year"`, `"reg_vehicles"`): field names such as
    `year` and `value` are reserved words in H2. Types: string →
    `varchar(maxLength or 255)`, integer → `integer`, number →
    `double precision`, boolean → `boolean`. Renaming, dropping or retyping a
    column needs an explicit instruction in the spec (data loss), otherwise
    stop and ask.
10c. **Emit the co-signing descriptor** when the service has a `consent.md`
    (other parties confirm or sign through emailed capability links), at
    `<pack>/backend/consent/<purpose>.yaml`. Copy the spec's tables
    into: `platform: 2`, `purpose`, `process`, `applicant: {firstName,
    lastName}`, `variables: {parties, confirmations, rejected, sent}`,
    `messages: {signature, send}`, `wording` (all ten keys: `party`,
    `rejection`, `unknownLink`, `alreadyRejected`, `alreadySent`,
    `alreadySigned`, `notWaiting`, `rejectedBack`, `notReady`,
    `notWaitingForSend`), optional `details: {<name>: {variable, type:
    string|number|names}}` and `documents: {<name>: {variable, category}}`.
    Show a co-signer only what they sign: a list of people is `names`, never
    `string`. The BPMN side stays as it is (`ConsentPartiesListener` field
    injection with the same variable names, the two messages, links minted
    for the same purpose); `ConsentController` serves the purpose, and
    `ConsentCatalog` refuses to start on anything outside this set.
11. **Emit the MCP training markdown** at `<service>/build/mcp-training.md`
    following the template in [§ 11.2](#112-mcp-trainingmd). Draw the
    "What this service does" content from the README's overview section,
    the "What to ask for" from the first user task's form Fields, and the
    "Status interpretation" mapping from the BPMN's end states.
12. **Update the aggregated services index** at
    `<pack>/docs/business/services/build/services.json` to include this service's
    `key`, `name`, `description`, `audience`, and a relative `manifestPath`
    to its `mcp-service.json`. List every service the skill knows about;
    the index is a full rewrite, alphabetical by `key`.
13. **Regenerate the mermaid diagram.** Run:
    ```sh
    node <tools>/bpmn-to-mermaid.mjs \
      <pack>/engine/processes/<service>/<service>.bpmn \
      --out <pack>/docs/business/services/<service>/README.md
    ```
    The script replaces the block between `<!-- bpmn-diagram:start -->` and
    `<!-- bpmn-diagram:end -->`. If the markers are missing, add them around
    the existing mermaid block before running the script.
14. **Report.** Summarise what changed in one short paragraph: service id,
    counts of forms / service tasks / decisions, list of generated files
    (including the three `build/` MCP artifacts), and the next manual step
    ("run `docker compose up --build` to test; the pack's image layers,
    the `mcp` one included, copy the new files in").

Never commit anything from this skill — that's a human step. The skill stops
at "files written, please test".

---

## 4. Validation rules

`cib7/src/test/java/com/poc/cib7/ServiceSpecsTest.java` enforces the
spec-first contract on every build: each BPMN service task has exactly one
`service-tasks/*.md` naming it in `**BPMN task id:**`; a spec's
`payload-template:` is the template the task uses and its ```` ```ftl ````
block equals the template file; each DMN has a `decisions/<id>.md`; and no
payload is inline JSON with `${...}` (JUEL cannot escape for JSON, so use a
template with `?json_string`). Emit specs and output together so it stays
green.

Reject the run if **any** of these fail. List every violation, don't
short-circuit on the first one.

| Rule | Check |
|---|---|
| Unique kebab-case ids | Every form id, service-task id, decision id, gateway id, sequence-flow id is `^[a-z][a-z0-9-]*$` and globally unique within the service spec. |
| Variable consistency | Every process variable mentioned in any spec file matches a row in the README's variables table (same casing, same type). Misspellings between `firstName` and `firstname` are caught here. |
| Roles are slash-less | `candidateGroups` and group references use the engine view (`applicant`, not `/applicant`) — see [project memory: cibseven-keycloak strips group-path slash](../../../docs/cib7.md#bpmn-files). |
| Large variables are `byte[]` | Anything declared with type `byte[]` in the README must carry a comment "stored bytes to spill to ACT_GE_BYTEARRAY" in BPMN output; anything > 4 kB **must** be declared `byte[]`. |
| DMN TTL | Every emitted `.dmn` carries `camunda:historyTimeToLive` (engine refuses deployment without it). |
| BPMN TTL | The `<bpmn:process>` element carries `camunda:historyTimeToLive`. Match the value in the README; default `P30D` if unspecified. |
| Form id ↔ formKey | Each user task in the README flow has a matching `forms/<id>.md` and the BPMN emits `camunda:formKey="react:<id>"`. |
| Service task ↔ spec file | Each service task in the README flow has a matching `service-tasks/<id>.md`. |
| Decision ↔ spec file | Each business rule task in the README flow has a matching `decisions/<id>.md` and the BPMN emits `camunda:decisionRef="<id>"` with `camunda:decisionRefBinding="deployment"` (the DMN ships in the same per-service deployment). |
| Service examples | Each `decisions/<id>.md` has an `## Examples` table (and `## Examples without \`<rule>\`` for a demo rule), each README with a State fee a `### Fee examples` table, each `data/<entity>.md` a `## Seed`, each form with a value schema `## Submission examples`, each form definition `## Behaviour examples`, each service task with a payload template an `## Example`, and a README may carry `## Flow scenarios`. They are the services' tests: `DecisionExamplesTest`, `FeeExamplesTest`, `RegistrySeedTest`, `SubmissionExamplesTest`, `TemplateExamplesTest`, `FlowScenariosTest` and the frontend's `src/pack/behaviour.test.ts` run them in `scripts/pack-check.sh`. A missing examples table fails the pack check; never write examples by reading the generated output back, write what the policy says. |
| Initiator pattern | User tasks owned by the initiator carry `camunda:assignee="${initiator}"`, not `candidateGroups`. The start event carries `camunda:initiator="initiator"`. |
| FreeMarker JSON safety | Generated `.json.ftl` files escape string values with `?json_string`. |
| MCP manifest fields | Every name in `mcp-service.json`'s `start.fields` and each `userTasks[].fields` is a property of the matching engine form schema, and every required property is offered (`mcp/src/services/pack.test.ts`). A manifest that fails this is refused at load and its service disappears from the agent's view. |
| MCP manifest user-task coverage | Every user task with a `camunda:formKey="react:<id>"` in the emitted BPMN has a matching `userTasks[]` entry in `mcp-service.json` with the same `formKey` (`mcp/src/services/pack.test.ts`, for every form with a schema). |
| Security: escaping | Every user-supplied value in a `.json.ftl` uses `?json_string`; in HTML bodies (PDF, email) `?html` as well. User values never go into a connector URL path unvalidated (docs/security.md rule 7). |
| Security: no client-minted secrets | Forms never generate tokens, link ids, payment references or any other credential in the browser (`crypto.randomUUID()` for a link token is a violation). Capability links are minted server-side (docs/security.md rule 3). |
| Security: system-owned variables | A form's `onComplete` variables and its MCP `userTasks[].fields` contain only the fields the form spec declares. DMN outputs, connector outputs, payment/consent state and config bean names (`busBaseUrl`, `frontendBaseUrl`, `pdf`) are never form output; a decision (`decision`, `medicalResult`, ...) is output only of the reviewer form that owns it; identity fields only of an applicant form whose process binds them in `IdentityFieldRegistry` (docs/security.md rule 2). |
| Variable policy coverage | `variable-policy.json` exists, its `processDefinitionKey` is the BPMN process id, and its `forms` keys equal the set of `camunda:formKey` ids in the emitted BPMN, no more and no fewer. `VariablePolicyFilesTest` fails the cib7 build otherwise. |
| Variable policy matches form and MCP | Each `forms.<id>` list equals the names derived from that form's Actions table (step 10), and equals the matching MCP `userTasks[].fields` plus its `notOffered`; `start` equals the MCP `start.fields` plus `start.notOffered`. `VariablePolicyFilesTest` (a pack check) holds them equal. A field the MCP manifest offers but the policy lacks makes `complete_task` fail with 403; a field the policy has but neither the form nor the README names is an open write. |
| Security: no wildcard grants | The spec never asks for engine grants; access comes from `camunda:assignee="${initiator}"` and `candidateGroups`. If a spec needs a new role, stop and ask (docs/security.md rule 1). |
| Security: endpoint class | Connector calls to the backend use `/api/internal/**` for anything that writes data or returns personal data (docs/security.md rule 5). |
| services.json completeness | `<pack>/docs/business/services/build/services.json` lists every service whose folder has a `build/mcp-service.json`. No orphan entries; no missing entries. |

---

## 5. BPMN authoring patterns

Namespaces always:

```xml
xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
```

Process element with TTL:

```xml
<bpmn:process id="<processKey>" name="<Display Name>" isExecutable="true"
              camunda:historyTimeToLive="P30D">
```

Start with initiator:

```xml
<bpmn:startEvent id="StartEvent_1" name="<Start label>"
                 camunda:initiator="initiator" />
```

Applicant (initiator) user task — owned by the starting user:

```xml
<bpmn:userTask id="Task_<Name>" name="<Display name>"
               camunda:formKey="react:<form-id>"
               camunda:assignee="${initiator}" />
```

Group-scoped user task — anyone in the group can pick it up:

```xml
<bpmn:userTask id="Task_<Name>" name="<Display name>"
               camunda:formKey="react:<form-id>"
               camunda:candidateGroups="<group>" />
```

HTTP service task (inline payload):

```xml
<bpmn:serviceTask id="Task_<Name>" name="<Display name>" camunda:asyncBefore="true">
  <bpmn:extensionElements>
    <camunda:connector>
      <camunda:connectorId>http-connector</camunda:connectorId>
      <camunda:inputOutput>
        <camunda:inputParameter name="url">${busBaseUrl}/path/${someVar}</camunda:inputParameter>
        <camunda:inputParameter name="method">GET</camunda:inputParameter>
        <camunda:inputParameter name="headers">
          <camunda:map>
            <camunda:entry key="Accept">application/json</camunda:entry>
          </camunda:map>
        </camunda:inputParameter>
        <camunda:outputParameter name="<outVar>">${S(response).prop('data').prop('field').stringValue()}</camunda:outputParameter>
      </camunda:inputOutput>
    </camunda:connector>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

HTTP service task with FreeMarker payload:

```xml
<camunda:inputParameter name="payload">
  <camunda:script scriptFormat="freemarker" resource="templates/<task-id>.json.ftl" />
</camunda:inputParameter>
```

DMN business rule task:

```xml
<bpmn:businessRuleTask id="Task_<Name>" name="<Display name>"
                       camunda:decisionRef="<decision-id>"
                       camunda:decisionRefBinding="deployment"
                       camunda:mapDecisionResult="singleEntry"
                       camunda:resultVariable="<outputVar>" />
```

Exclusive gateway with `default`:

```xml
<bpmn:exclusiveGateway id="Gateway_<Name>" name="<Question?>"
                       default="Flow_<Default>" />
<bpmn:sequenceFlow id="Flow_<Branch>" name="<label>"
                   sourceRef="Gateway_<Name>" targetRef="<Target>">
  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${<expr>}</bpmn:conditionExpression>
</bpmn:sequenceFlow>
```

Non-interrupting timer boundary event (repeating reminder):

```xml
<bpmn:boundaryEvent id="BoundaryEvent_<Name>" name="<label>"
                    attachedToRef="Task_<Owner>" cancelActivity="false">
  <bpmn:timerEventDefinition>
    <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">R/PT2M</bpmn:timeCycle>
  </bpmn:timerEventDefinition>
</bpmn:boundaryEvent>
```

JUEL variables exposed by the engine (use these in URLs; don't hard-code):

| JUEL | Source | Pattern |
|---|---|---|
| `${busBaseUrl}` | `BusConfiguration.java` | the integration bus — ALL outbound HTTP goes here |
| `${frontendBaseUrl}` | `FrontendConfiguration.java` | browser links embedded in emails |
| `${pdf}` | `PdfHelper.java` bean | `${pdf.decode(...)}`, `${pdf.encode(...)}` |

**All outbound HTTP goes to `${busBaseUrl}`** — the engine never addresses
Mailpit, pdf-renderer, or the backend directly. The `esb` service (Apache
Camel, `esb/routes/*.yaml`) routes each path to the real downstream system:

| Path on `${busBaseUrl}` | Downstream | Use |
|---|---|---|
| `/api/v1/send` | Mailpit | send email |
| `/render` | pdf-renderer | render a PDF |
| `/api/public/**` | backend | public reference data (vehicle catalog) |
| `/api/internal/**` | backend | documents (move-pending, server-upload), case index |

Two rules when generating service tasks:
- **Never** add an `X-Internal-Token` header to `/api/internal/**` calls — the
  bus injects it. Emitting it would reference a deleted bean and break the task.
- A backend endpoint the engine calls that writes data or returns personal data
  goes under `/api/internal/**`, never `/api/public/**` (docs/security.md rule 5).
- If the spec needs a NEW external integration on a new path, add a declarative
  route to `esb/routes/` (YAML) rather than a hand-written `*Configuration.java`
  bean; the engine still just calls `${busBaseUrl}/<new-path>`.

---

## 6. DMN authoring patterns

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
             xmlns:camunda="http://camunda.org/schema/1.0/dmn"
             id="Definitions_<Name>" name="<Display>" namespace="http://camunda.org/schema/1.0/dmn">
  <decision id="<decision-id>" name="<Display>" camunda:historyTimeToLive="P30D">
    <decisionTable id="DecisionTable_<Name>" hitPolicy="FIRST">
      <input id="Input_<X>" label="<X>" camunda:inputVariable="<varName>">
        <inputExpression id="InputExpression_<X>" typeRef="integer"><text><varName></text></inputExpression>
      </input>
      <output id="Output_<Y>" label="<Y>" name="<outputVar>" typeRef="string" />
      <rule id="Rule_<Name>">
        <inputEntry id="..."><text>< 18</text></inputEntry>
        <outputEntry id="..."><text>"review"</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
```

Hit policies the analyst can ask for: `FIRST` (most common), `UNIQUE`,
`COLLECT`. Anything else, stop and ask.

---

## 7. FreeMarker payload templates

Path: `<pack>/engine/templates/<task-id>.json.ftl`: the JSON the
connector sends, nothing else.

Rules:

- **Always** escape strings that hit JSON with `?json_string`.
- Default every process variable that might be null with `!""` (string) or
  `!0` (number).
- For `byte[]` attachments, re-encode with `${pdf.encode(varName)}`.
- **A document is not generated.** An email body or a PDF is a
  hand-designed document in `<pack>/engine/documents/`
  (`email/<name>.ftl`, plain text; `pdf/<name>.ftlh`, HTML on the shared
  `/_brand.ftlh` layout). The generated payload only wraps it:
  `"Text": "${documents.text("<name>", execution)?json_string}"` or
  `"html": "${documents.html("<name>", execution)?json_string}"`. The
  service-task spec names the document; if the document does not exist yet,
  write a first version from the spec's description and say so, since a
  designer owns it from then on. Never `<#include>` in a payload template:
  the engine's FreeMarker has no template loader.

```ftl
<#-- what this payload is for; the body is documents/email/<name>.ftl -->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "<issuer> POC" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "<subject>",
  "Text":    "${documents.text("<name>", execution)?json_string}"
}
```

---

## 8.0 Form definition v1

The default output for a form. The core renderer
(`frontend/src/forms/schema/SchemaForm.tsx`, format in `definition.ts`)
fetches it as `/pack/forms/<id>.json` and refuses a definition outside v1 as
a whole. Map the spec one to one:

| Spec | Definition |
|---|---|
| header `Form id`, `Texts` (i18n namespace), the process id | `form`, `i18n`, `process`; `"version": 1` |
| Intro table | `intro: {edit, readOnly}` (text keys) |
| Summary table | `summary: [{label, variable, format, show}]`; format `text` `number` `currency` `decision`; show `always` `readOnly` `editing` (a non-`always` row is hidden while its value is empty). Instead of a plain variable a row can be: a template, `{label, template: <key>, variables: [...]}` (one text interpolating several variables, missing ones as `—`); options, `{label, variable, options: {<value>: <key>}}` (a coded value shown as text); or a list, `{label, variable, item: <key>}` (a JSON list, each entry through a key interpolating its own properties) |
| Fields table, input types `display` `text` `textarea` `number` `email` `select` `file` `contacts` `radio` `rows` | `fields: [{name, label, type, format, hint, placeholder, rows, identity, min, max, decimal, step, default, suffix, revealedBy, requiredMessage, rangeMessage, emailMessage, requiredWhenListed, source, file, contacts, options, table}]`. `text` takes `suffix` (a word appended on submit when missing, e.g. `OÜ`) and `fixedLength` (a value of exactly N characters; the input shows an underscore per missing character), as do `rows` columns; `number` takes `decimal: true` for fractions and `step`; `default` is the starting value of a `text`, `number` or `radio` field; `radio` takes `options: [{value, label, hint}]`; `rows` takes `table: {legend, add, removeAria, minRows, columns: [{name, placeholder, format, maxLength, fixedLength}], requiredMessage, incompleteMessage, formatMessage}` (column format `personalCodeEE`; the format message interpolates the row's values by column name); `identity: true` for "identity (from account)" text or email fields; `number` takes `min`/`max`/`rangeMessage`; `email` takes `emailMessage` and, for a conditional rule "list X non-empty → email", `requiredWhenListed: {field, message}`; `select` takes `source: {registry, value, label, formats, error}`; `file` takes `file: {category, accept, maxBytes, dropLabel, existingVariable, existingFilename}`; `contacts` takes `contacts: {legend, namePlaceholder, emailPlaceholder, add, removeAria, nameMessage, emailMessage, duplicateMessage, notField}`; `revealedBy` = the action id that reveals the field |
| Resubmission line (send-back loop target) | `resubmittedWhen: "sendBackReason"`, `banner: {title, variable}`, `intro.resubmission`, the actions' `resubmitLabel` |
| Notices table | `notices: [{label, variable, show}]` |
| Actions table | `actions: [{id, label, resubmitLabel, style, workingLabel, confirmLabel, complete}]`; `complete` maps each `complete-with` variable to `{value, type}` or `{field, type}`; `contacts`, `file` and `rows` fields complete as `Json` |

Texts are keys in the form's locale namespace; write the English text into
`<pack>/frontend/locales/en/<namespace>.json` and the Arabic into
`ar/<namespace>.json`, and list the namespace in `catalog.json` (§ 8.1). A
key with a namespace prefix (`common:actions.submit`) is a core text and
stays in the core. A spec that needs an input type, format or behaviour
outside this table is a spec gap: stop and ask. The answer is either a new
renderer feature in core (a platform API change) or, rarely,
`Renderer: tsx`.

## 8.1 Catalog and display names

The SPA holds nothing service-specific. At startup it reads the pack's
`/pack/catalog.json` (`frontend/src/pack/catalog.ts`, catalog format v1,
strict like the form definitions) and the texts of every namespace it lists,
before the first render.

`<pack>/frontend/catalog.json`, rewritten in full from every service:

```json
{
  "$comment": "Generated from the Catalog sections of <pack>/docs/business/services/*/README.md. Do not hand-edit. Read by frontend/src/pack/catalog.ts (catalog format v1).",
  "version": 1,
  "namespaces": ["catalog", "names", "<every form namespace, alphabetical>"],
  "services": { "<processKey>": { "category": "<category>", "issuer": "<issuer-id>" } },
  "issuers": { "<issuer-id>": { "tone": "primary | ok" } }
}
```

- **Category** is one of `business` `family` `property` `travel` `social`
  `other` (the core's life-event tiles). A service the catalog does not
  list falls under `other`.
- **Issuer** is the authority that bills the state fee; the `tone` colours
  its payment header. Several services can share one issuer.
- **`locales/<lang>/catalog.json`** holds the texts:
  `services.<processKey>.summary` (the line under the service name),
  `services.<processKey>.fee` (the fee name on the payment page) and
  `issuers.<issuer-id>.name` / `.sub`.
- **`locales/<lang>/names.json`** translates display names: every `name=`
  of a process, task or activity in the generated BPMN, the English name
  as the key (dots and colons are fine), e.g.
  `{"Vehicle Registration": "تسجيل المركبات"}`. Write the English name as
  the `en` value. A name without an entry shows in English.

The README's Catalog section gives category, summary, fee name and issuer
in both languages. If it is missing, stop and ask. `frontend/src/pack/pack.test.ts`
checks that every namespace exists in both languages with the same keys
and that every text a form definition uses exists.

## 8. React form template

One file per form id, under `frontend/src/forms/<form-id>/<PascalCase>Form.tsx`.
Must implement the `FormProps` contract from
[`frontend/src/forms/types.ts`](../../../frontend/src/forms/types.ts):

```tsx
import { useState, type FormEvent } from 'react';
import type { FormProps } from '../types';

export default function <PascalCase>Form({
  data,
  onComplete,
  submitting,
  readOnly,
}: FormProps) {
  // One useState per field defined in the spec.
  const [<field>, set<Field>] = useState((data.<field> as <T>) ?? <default>);

  // If the form is on the send-back loop, show the reason banner.
  const sendBackReason = (data.sendBackReason as string) ?? '';
  const isResubmission = Boolean(sendBackReason) && !readOnly;

  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    // Client-side validation as declared in the spec.
    // …
    await onComplete({
      <var>: { value: <value>, type: '<CamundaType>' },
      // Forms on the send-back loop clear the reason on resubmit.
      sendBackReason: { value: '', type: 'String' },
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit}>
      {isResubmission && (
        <div className="form-banner form-banner-warn">
          <strong>Sent back for corrections.</strong>
          <p className="form-banner-body">{sendBackReason}</p>
        </div>
      )}

      <p className="form-intro">{/* short intro from spec */}</p>

      <label className="field">
        <span className="field-label"><Label></span>
        <input
          className="field-input"
          value={<field>}
          onChange={(e) => set<Field>(e.target.value)}
          disabled={readOnly}
        />
      </label>

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        </div>
      )}
    </form>
  );
}
```

Camunda variable types: `String`, `Integer`, `Long`, `Double`, `Boolean`,
`Date` (ISO-8601 string), `Json` (Spin-typed object). Match the type declared
in the README's variables table.

Review-style forms (a read-only summary plus an outcome button row) are
form definitions now, not TSX: see
[`vehicle-review.json`](../../../packs/test/frontend/forms/vehicle-review.json),
whose actions **Approve** / **Send back** complete with different `decision`
values.

---

## 9. Registry rewrite

Rewrite `frontend/src/forms/registry.ts` end-to-end. Keep the comment block,
keep `parseFormId`. Sort imports and entries alphabetically by form id:

```ts
import type { ComponentType } from 'react';
import type { FormProps } from './types';
import <PascalCase>Form from './<form-id>/<PascalCase>Form';
// … (one import per form across every service)

/**
 * Maps a logical form id to a React component. The form id is the part of the
 * BPMN `camunda:formKey` after the `react:` prefix (spec §6.1, §8.3).
 *
 * Generated by .claude/skills/service-builder — do not edit by hand.
 */
export const formRegistry: Record<string, ComponentType<FormProps>> = {
  '<form-id>': <PascalCase>Form,
  // … alphabetical
};

export function parseFormId(formKey: string | null | undefined): string | null {
  if (!formKey) return null;
  const prefix = 'react:';
  return formKey.startsWith(prefix) ? formKey.slice(prefix.length) : formKey;
}
```

---

## 10. Mermaid diagram

After writing the BPMN, regenerate the diagram block in the service README:

```sh
node <tools>/bpmn-to-mermaid.mjs \
  <pack>/engine/processes/<service>/<service>.bpmn \
  --out <pack>/docs/business/services/<service>/README.md
```

If the README is brand new, **first** add the marker block under the
`## Flow diagram` heading:

```markdown
<!-- bpmn-diagram:start -->
<!-- bpmn-diagram:end -->
```

Then run the script — it fills it in.

---

## 11. MCP manifest authoring

The `mcp/` sidecar (its consumer code is at
[`mcp/src/services/manifest.ts`](../../../mcp/src/services/manifest.ts))
serves the LLM-callable tools. To make a service MCP-callable, the skill
generates three artifacts: a per-service manifest (data), per-service
training markdown (prose), and an aggregated index.

The reference output for `vehicleRegistration` lives at
[`<pack>/docs/business/services/vehicle-registration/build/mcp-service.json`](../../../packs/test/docs/business/services/vehicle-registration/build/mcp-service.json)
and
[`<pack>/docs/business/services/vehicle-registration/build/mcp-training.md`](../../../packs/test/docs/business/services/vehicle-registration/build/mcp-training.md).
Read them before generating a new service — same shape, same field order.

### 11.1 `mcp-service.json`

Path: `<pack>/docs/business/services/<service>/build/mcp-service.json`.
Format 2 (docs/platform-api.md). The manifest holds no value rules: the
sidecar validates with the engine's own form schemas (step 10's
`schemas/<form-id>.json` and `schemas/start.json`, core definitions
inlined), so the manifest only says which fields an agent is offered and
what each one means. Schema:

```json
{
  "version": 2,
  "key": "<processKey>",
  "name": "<Human-readable name>",
  "description": "<One paragraph — what the service does, when an applicant would use it. Drawn from the README's 'What this service does' section, condensed.>",
  "audience": "<applicant | civil-servant | …>",
  "candidateGroups": ["<group1>", "..."],
  "initialTask": {
    "formKey": "<first user task's form id>",
    "audience": "<applicant>",
    "name": "<First user task display name>"
  },
  "start": {
    "description": "<one sentence, mention `initiator` and identity fields are engine-set>",
    "fields": { "<varName>": "<what it means, in words an agent can ask the user with>" }
  },
  "userTasks": [
    {
      "formKey": "<form-id from forms/<id>.md>",
      "name": "<task display name>",
      "audience": "<applicant | civil-servant | ...>",
      "description": "<one sentence about what the audience is doing on this task>",
      "requiredDocuments": [ { "category": "<applicant category>", "writeTo": "<field>", "accept": ["application/pdf"], "maxBytes": 10485760, "description": "<...>" } ],
      "fields": { "<varName>": "<description>" },
      "notOffered": ["<varName>", "..."]
    }
  ]
}
```

**`start.fields`** are the variables `start_process` may pre-fill: exactly
the policy's `start` list (step 10), each a property of `schemas/start.json`.

**`userTasks[].fields`**, one entry per user task in the flow, are the
variables `complete_task` may send: the form's policy list minus the
fields the README's "Variable write policy" section names as SPA-only
(identity fields the engine fills from the account, form-internal state
the agent surface deliberately leaves out). Those SPA-only fields are the
task's **`notOffered`** list (omit it when empty), so policy = `fields` +
`notOffered`, exactly; the same holds for `start`. Rules the loader enforces, and
`mcp/src/services/pack.test.ts` checks:

- every field is a property of the form's schema, or one its `allOf`
  conditions add (e.g. `sendBackReason` on a review form);
- every field the schema `required`s is offered;
- every `requiredDocuments[].writeTo` is an offered field;
- every form with a schema has a `userTasks` entry.

A description says what the value means and where it comes from, never a
rule the schema already states (type, pattern, range): those reach the agent
from the schema.

NEVER offer variables that are written by service tasks, DMNs, or
correlated message events — those are engine-internal and the agent has no
business setting them through `complete_task`.

### 11.2 `mcp-training.md`

Path: `<pack>/docs/business/services/<service>/build/mcp-training.md`. Template
(fill from the README and form specs):

```markdown
# <service> — guidance for the LLM

<One short paragraph from the README's "What this service does" overview,
restated for an audience that's an LLM-driven assistant, not a developer.>

## What to ask the user for

To start a <service>, you need these pieces of information:

- **<field>** — <one-line meaning + any constraints worth knowing>
- ...

Do NOT include `initiator` in your start_process call. The engine sets it
from the authenticated Keycloak user automatically.

## Auto-approval rule (omit if the service has no DMN)

<Summarise the DMN: which input ranges auto-approve vs route to human review.>

## After start_process

The engine creates the "<first user task name>" user task assigned to the
applicant. The variables you passed at start time are pre-filled.

Once `complete_task` is wired (eng-review T9, already shipped at this point
of the project), you can finish the task directly via MCP. Otherwise the
applicant confirms through the React portal at http://localhost:3000.

If the case is sent back with a reason, the applicant returns to the same
task to correct and resubmit. The send-back reason surfaces via
`query_user_history('sendBackReason')`.

## Status interpretation

<Map process states to user-facing language:
  Process instance `running`, "<first task>" open → applicant hasn't confirmed yet.
  Process instance `running`, no tasks open → in transit through a service task.
  Process instance `running`, "<review task>" open → with civil servant.
  Process instance `completed` → either auto-approved or accepted by civil servant.>

## Common applicant questions

<2–4 questions that come up — drawn from the README's "Known trade-offs"
and the analyst's domain context. If you don't have material, omit this
section rather than fabricating.>
```

The skill ALWAYS regenerates this file from the README on every run. If the
analyst wants nuanced LLM-facing prose that doesn't fit in the README's
overview, they should add a `## LLM guidance` section to the README; the
skill copies that block verbatim into the training markdown's overview
paragraph.

### 11.3 `services.json` (aggregated index)

Path: `<pack>/docs/business/services/build/services.json`. The MCP sidecar reads
this as the top-level discovery surface (also served at
`/.well-known/mcp/services.json` via nginx — see
[`frontend/nginx.conf`](../../../frontend/nginx.conf)). Schema:

```json
{
  "version": 1,
  "services": [
    {
      "key": "<processKey>",
      "name": "<Human-readable name>",
      "description": "<one-line summary>",
      "audience": "<applicant | civil-servant | ...>",
      "manifestPath": "<service>/build/mcp-service.json"
    }
  ]
}
```

Order: alphabetical by `key`. Full rewrite every run; don't try to merge.
List EVERY service whose folder has a `build/mcp-service.json`, not just
the one being regenerated this run — that's how new services appear in the
index, but it's also how removed services drop out.

---

## 12. Modifications

When the analyst edits a spec, this skill is run again. Idempotency rules:

- A re-run on an unchanged spec writes no diffs.
- Renaming a form id deletes the old `frontend/src/forms/<old>/` folder
  before writing the new one (ask first — a `git mv` from outside may be
  preferred for history).
- Removing a form / service-task / decision from the spec deletes the
  generated artifact (ask first) — both the source-tree files (BPMN, React,
  FreeMarker) AND the corresponding `userTasks[]` entry in
  `mcp-service.json`.
- Adding a new service registers its forms in `registry.ts` alongside
  existing ones; existing entries from other services stay. The new
  service's `mcp-service.json` is generated fresh; `services.json` is
  rewritten to include the new entry alphabetically.
- Removing a service deletes its `cib7/...` outputs, its `frontend/src/forms/`
  folders, AND removes its entry from `services.json`. The `<service>/build/`
  folder is also deleted so the MCP container doesn't surface a stale
  manifest on its next image build (ask first).
- `mcp-training.md` is regenerated from the README on every run. If the
  analyst hand-tuned it, the changes are LOST — they should move the prose
  to the README's `## LLM guidance` section instead (which the skill copies
  verbatim).

The generated files carry no "do not edit" header beyond the registry
comment, because round-tripping through a BPMN modeller is allowed for the
BPMN's BPMNDI layout block. Treat BPMNDI as best-effort; the modeller will
overwrite it on re-save and that's fine.

---

## 13. Testing handoff

After a successful run, print:

```
✓ Generated <N> file(s) for service "<service-key>"

Next:
  docker compose up --build
  → PartA: http://localhost:3000 as bart / bart
  → PartB: http://localhost:3000 as homer / homer
  → Cockpit:    http://localhost:8080/camunda/app/cockpit/  (incidents)
  → Mailpit:    http://localhost:8025                       (notifications)
  → MCP via Claude Desktop: configured per /mcp/README.md   (LLM round trip)

The mcp container COPYs <pack>/docs/business/services/ at image build time, so the
new manifest + training markdown ship into the image automatically. Verify
with:
  docker exec cib7-poc-mcp ls /app/services-spec/<service>/build/
  curl http://localhost:3000/.well-known/mcp/services.json

When the flow works end-to-end, commit the spec and generated files together:
  git add <pack>/docs/business/services/<service>/ \
          <pack>/docs/business/services/build/services.json \
          <pack>/engine/processes/ \
          <pack>/engine/templates/ \
          frontend/src/forms/
  git commit -m "<service>: <one-line summary>"
```

Don't run `docker compose` or `git commit` from the skill — those stay with
the human.
