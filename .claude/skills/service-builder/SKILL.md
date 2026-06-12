---
name: service-builder
description: |
  Generate or modify a CIB seven business service from its markdown spec under
  docs/business/services/<service>/. Reads README.md, forms/*.md, service-tasks/*.md,
  and decisions/*.md; emits BPMN, DMN, FreeMarker payload templates, React form
  components, registry entries, MCP manifest + LLM training markdown for the
  /mcp microservice, and a regenerated mermaid diagram. Use when asked to "build
  the service", "generate from the spec", "scaffold a new service", "regenerate
  the BPMN", "regenerate the MCP manifest", or after editing any file under
  docs/business/services/.
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
the markdown under `docs/business/services/<service>/`; everything else is
generated. The goal is that **regenerating the same spec produces the same
output**, so modifications work by editing the spec and re-running.

Two canonical references — read both before generating a new service:

- **`person-registration`** — the older, denser spec (everything in
  `README.md`, no `forms/` or `decisions/` subfolders). Useful for seeing
  the upper bound of what a service can do (multi-instance subprocess,
  receive tasks, PDF generation, public confirmation page).
- **`business-registration`** — the cleaner reference (separate `forms/`,
  `service-tasks/`, `decisions/`, `build/` folders). Useful for seeing
  the recommended file layout and the spec-first × MCP pipeline
  end-to-end.

Per-service files (paths under `cib7-react-poc/`):

| | person-registration | business-registration |
|---|---|---|
| Spec | [`docs/business/services/person-registration/README.md`](../../../docs/business/services/person-registration/README.md) | [`docs/business/services/business-registration/README.md`](../../../docs/business/services/business-registration/README.md) |
| BPMN | [`cib7/src/main/resources/processes/person-registration.bpmn`](../../../cib7/src/main/resources/processes/person-registration.bpmn) | [`cib7/src/main/resources/processes/business-registration/business-registration.bpmn`](../../../cib7/src/main/resources/processes/business-registration/business-registration.bpmn) |
| DMN | [`cib7/.../auto-approval.dmn`](../../../cib7/src/main/resources/processes/auto-approval.dmn) | [`cib7/.../business-auto-approval.dmn`](../../../cib7/src/main/resources/processes/business-registration/business-auto-approval.dmn) |
| Forms | [`personal-details/PersonalDetailsForm.tsx`](../../../frontend/src/forms/personal-details/PersonalDetailsForm.tsx), [`review-application/ReviewApplicationForm.tsx`](../../../frontend/src/forms/review-application/ReviewApplicationForm.tsx) | [`business-details/BusinessDetailsForm.tsx`](../../../frontend/src/forms/business-details/BusinessDetailsForm.tsx), [`review-business-registration/ReviewBusinessRegistrationForm.tsx`](../../../frontend/src/forms/review-business-registration/ReviewBusinessRegistrationForm.tsx) |
| MCP manifest | [`build/mcp-service.json`](../../../docs/business/services/person-registration/build/mcp-service.json) | [`build/mcp-service.json`](../../../docs/business/services/business-registration/build/mcp-service.json) |
| MCP training | [`build/mcp-training.md`](../../../docs/business/services/person-registration/build/mcp-training.md) | [`build/mcp-training.md`](../../../docs/business/services/business-registration/build/mcp-training.md) |

Cross-service artifacts:

- Form registry: [`frontend/src/forms/registry.ts`](../../../frontend/src/forms/registry.ts) (full rewrite per run)
- Aggregated MCP index: [`docs/business/services/build/services.json`](../../../docs/business/services/build/services.json)
- Mermaid generator: [`scripts/bpmn-to-mermaid.mjs`](../../../scripts/bpmn-to-mermaid.mjs)

The top-level [`README.md` § "Add or modify a service"](../../../README.md#add-or-modify-a-service)
explains the human workflow around this skill.

---

## 1. Inputs — the spec contract

A service folder looks like:

```
docs/business/services/<service>/
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
| `<service>/README.md` (flow section) | `cib7/src/main/resources/processes/<service>/<service>.bpmn` |
| `<service>/decisions/<id>.md` | `cib7/src/main/resources/processes/<service>/<id>.dmn` |
| `<service>/service-tasks/<id>.md` with payload-template body | `cib7/src/main/resources/templates/<id>.json.ftl` |
| `<service>/forms/<id>.md` | `frontend/src/forms/<id>/<PascalCase>Form.tsx` |
| Every form across every service | `frontend/src/forms/registry.ts` (full rewrite, alphabetical by id) |
| `<service>.bpmn` after regeneration | mermaid block inside `<service>/README.md` |
| `<service>/README.md` (variables + forms) + `<service>/forms/*.md` | `<service>/build/mcp-service.json` (MCP manifest + JSON Schemas; § 11) |
| `<service>/README.md` + form audiences | `<service>/build/mcp-training.md` (LLM training markdown; § 11) |
| Every `<service>/build/mcp-service.json` across every service | `docs/business/services/build/services.json` (aggregated MCP index; § 11) |

The three `build/`-typed outputs above are the contract with the `mcp/` Node
sidecar — its `Dockerfile` COPYs `docs/business/services/` into the image
and the loader walks every `<service>/build/mcp-service.json + mcp-training.md`
pair. See [`mcp/src/services/manifest.ts`](../../../mcp/src/services/manifest.ts)
for the consumer side.

**Deployment convention:** each service's BPMN + DMN files go into their own
`cib7/src/main/resources/processes/<service>/` folder (folder name = spec
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
   `docs/business/services/`.
2. **Read every spec file** in the service folder. Build an in-memory model:
   process id (camelCase = folder name in kebab transformed; usually written
   explicitly in the README), start event, nodes (user tasks, service tasks,
   business rule tasks, gateways, boundary events, end events), sequence
   flows with conditions and defaults, process variables, FreeMarker payload
   templates.
3. **Validate** against [§ 4](#4-validation-rules). On failure, list every
   violation in one message and stop — don't generate partial output.
4. **Emit BPMN** at `cib7/src/main/resources/processes/<service>/<service>.bpmn` using
   the patterns in [§ 5](#5-bpmn-authoring-patterns). Include BPMNDI layout
   bounds so Cockpit can render the diagram; pack them on a horizontal
   waterline (y=160, x advances by 140 per task) and let the modeller adjust
   later if needed. Don't try to be clever — readable lanes beat dense lanes.
5. **Emit DMN** for each `decisions/<id>.md` at
   `cib7/src/main/resources/processes/<service>/<id>.dmn` using the pattern in
   [§ 6](#6-dmn-authoring-patterns). Every DMN **must** carry
   `camunda:historyTimeToLive` matching the BPMN's TTL.
6. **Emit FreeMarker payloads** for each `service-tasks/<id>.md` that
   declares a `payload-template:` body — write
   `cib7/src/main/resources/templates/<id>.json.ftl`. Inline payloads
   (short, no template marker) go inside the BPMN as `<camunda:inputParameter
   name="payload">…</camunda:inputParameter>` instead.
7. **Emit React forms.** One folder per `forms/<id>.md` at
   `frontend/src/forms/<id>/<PascalCase>Form.tsx`. Follow the template in
   [§ 8](#8-react-form-template). Always handle the `readOnly` prop, the
   `data` defaults, the typed `onComplete` variables, and (for forms on the
   send-back loop) the `sendBackReason` banner pattern.
8. **Rewrite the registry.** Scan every `forms/<id>.md` across every
   service. Rewrite `frontend/src/forms/registry.ts` end-to-end with one
   import per form id (alphabetical) and matching entries. Don't try to do
   line-level edits — a clean rewrite beats merge conflicts.
9. **Emit the MCP service manifest.** Write
   `<service>/build/mcp-service.json` following the schema in
   [§ 11.1](#111-mcp-servicejson). Derive the start-time `variables`
   schema from the first user task's form spec (which is what
   `start_process` pre-fills). Derive each `userTasks[].schema` from the
   matching `forms/<id>.md` Fields table. Use the variable types declared
   in the README's process-variables table to constrain the JSON Schema
   `type` keyword.
10. **Emit the MCP training markdown** at `<service>/build/mcp-training.md`
    following the template in [§ 11.2](#112-mcp-trainingmd). Draw the
    "What this service does" content from the README's overview section,
    the "What to ask for" from the first user task's form Fields, and the
    "Status interpretation" mapping from the BPMN's end states.
11. **Update the aggregated services index** at
    `docs/business/services/build/services.json` to include this service's
    `key`, `name`, `description`, `audience`, and a relative `manifestPath`
    to its `mcp-service.json`. List every service the skill knows about;
    the index is a full rewrite, alphabetical by `key`.
12. **Regenerate the mermaid diagram.** Run:
    ```sh
    cd scripts && node bpmn-to-mermaid.mjs \
      ../cib7/src/main/resources/processes/<service>/<service>.bpmn \
      --out ../docs/business/services/<service>/README.md
    ```
    The script replaces the block between `<!-- bpmn-diagram:start -->` and
    `<!-- bpmn-diagram:end -->`. If the markers are missing, add them around
    the existing mermaid block before running the script.
13. **Report.** Summarise what changed in one short paragraph: service id,
    counts of forms / service tasks / decisions, list of generated files
    (including the three `build/` MCP artifacts), and the next manual step
    ("run `docker compose up --build` to test, including the `mcp` container
    which COPYs the new manifests at build time").

Never commit anything from this skill — that's a human step. The skill stops
at "files written, please test".

---

## 4. Validation rules

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
| Initiator pattern | User tasks owned by the initiator carry `camunda:assignee="${initiator}"`, not `candidateGroups`. The start event carries `camunda:initiator="initiator"`. |
| FreeMarker JSON safety | Generated `.json.ftl` files escape string values with `?json_string`. |
| MCP manifest variable consistency | Every variable name + type in `mcp-service.json`'s start `variables` schema and each `userTasks[].schema` matches the README's process-variables table. Drift here means start_process or complete_task will reject the LLM's input while the React form succeeds (or vice versa) — silent contract break. |
| MCP manifest user-task coverage | Every user task with a `camunda:formKey="react:<id>"` in the emitted BPMN has a matching `userTasks[]` entry in `mcp-service.json` with the same `formKey`. The skill rejects manifests where a form exists in the React tree but not in the MCP manifest. |
| services.json completeness | `docs/business/services/build/services.json` lists every service whose folder has a `build/mcp-service.json`. No orphan entries; no missing entries. |

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
        <camunda:inputParameter name="url">${baseUrl}/path/${someVar}</camunda:inputParameter>
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
| `${mailApiBaseUrl}` | `MailConfiguration.java` | Mailpit `/api/v1/send` |
| `${pdfApiBaseUrl}` | `PdfConfiguration.java` | `pdf-renderer` `/render` |
| `${pdf}` | `PdfHelper.java` bean | `${pdf.decode(...)}`, `${pdf.encode(...)}` |

If the spec asks for a new external integration that needs an environment-driven
base URL, **stop and ask** — that requires a hand-written `*Configuration.java`
bean which is out of scope for this skill.

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

Path: `cib7/src/main/resources/templates/<task-id>.json.ftl`. Pattern:

```ftl
<#-- short description of what process variables are in scope -->
<#assign body>Hi ${firstName!""},
…
</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}" } ],
  "Subject": "<subject>",
  "Text":    "${body?json_string}"
}
```

Rules:

- **Always** escape strings that hit JSON with `?json_string`.
- Default every process variable that might be null with `!""` (string) or
  `!0` (number).
- For `byte[]` attachments, re-encode with `${pdf.encode(varName)}`.

---

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

For review-style forms (`readOnly`-by-default field display + an outcome
button row), look at
[`ReviewApplicationForm.tsx`](../../../frontend/src/forms/review-application/ReviewApplicationForm.tsx)
— same `FormProps`, but every input is `disabled` and the action row offers
**Accept** / **Send back** that complete with different `decision` values.

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
cd scripts && node bpmn-to-mermaid.mjs \
  ../cib7/src/main/resources/processes/<service>/<service>.bpmn \
  --out ../docs/business/services/<service>/README.md
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

The hand-written reference for `personRegistration` lives at
[`docs/business/services/person-registration/build/mcp-service.json`](../../../docs/business/services/person-registration/build/mcp-service.json)
and
[`docs/business/services/person-registration/build/mcp-training.md`](../../../docs/business/services/person-registration/build/mcp-training.md).
Read them before generating a new service — same shape, same field order.

### 11.1 `mcp-service.json`

Path: `docs/business/services/<service>/build/mcp-service.json`. Schema:

```json
{
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
  "variables": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "title": "<service> start-time variables",
    "description": "<one sentence, mention `initiator` is engine-set>",
    "properties": {
      "<varName>": { "type": "<...>", "description": "<...>", "<constraints>": "<...>" }
    },
    "required": ["<...>"],
    "additionalProperties": false
  },
  "userTasks": [
    {
      "formKey": "<form-id from forms/<id>.md>",
      "name": "<task display name>",
      "audience": "<applicant | civil-servant | ...>",
      "description": "<one sentence about what the audience is doing on this task>",
      "schema": { /* JSON Schema for the form variables */ }
    }
  ]
}
```

**Deriving the `variables` schema** (used by `start_process`):

The schema for start-time variables comes from the FIRST user task in the
flow — `start_process(key, variables)` pre-fills exactly those values, and
the engine sets them as process variables before the first user task is
created. Resolution order:

1. If the first user task has its own form spec at `<service>/forms/<id>.md`
   with a Fields table, derive from there.
2. Else, read the variables in the README's process-variables table that
   are marked `Set by <first user task>`.

Type mapping (spec type → JSON Schema):

| Spec type | JSON Schema | Notes |
|---|---|---|
| `String` | `{"type": "string"}` | `minLength: 1` if Required = yes |
| `Integer` | `{"type": "integer"}` | Use spec's `Validation` column for `minimum`/`maximum` |
| `Long` | `{"type": "integer"}` | |
| `Double` | `{"type": "number"}` | |
| `Boolean` | `{"type": "boolean"}` | |
| `Date` | `{"type": "string", "format": "date-time"}` | |
| `Json` (list) | `{"type": "array", "items": {...}}` | Item schema from the README hint |
| `Json` (object) | `{"type": "object", "properties": {...}}` | Property schemas from the README hint |
| `byte[]` | (skip — exclude from MCP schema) | LLM never passes binary; binary vars are produced by service tasks (PDFs etc.) |

Always set `additionalProperties: false` on the root object — strictness
catches LLM hallucinations cleanly via Ajv `INVALID_VARIABLES`.

**Deriving each `userTasks[].schema`** (used by `complete_task`):

Walk every user task in the flow. For each one:
1. The schema comes from `<service>/forms/<id>.md`'s Fields table.
2. The `complete-with` row in the form's Actions table tells you which
   fields are actually sent on submit (some forms compute synthetic vars
   like `decision` from a button click — include those).
3. For review-style forms with multiple action buttons (Accept / Send back),
   produce a schema with an `enum` on `decision` and a conditional `required`
   for `sendBackReason` (see the canonical
   `review-application` entry in `person-registration/build/mcp-service.json`).

NEVER include variables in `userTasks[].schema` that are written by service
tasks, DMNs, or correlated message events — those are engine-internal and
the LLM has no business setting them through `complete_task`.

### 11.2 `mcp-training.md`

Path: `docs/business/services/<service>/build/mcp-training.md`. Template
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

Path: `docs/business/services/build/services.json`. The MCP sidecar reads
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

The mcp container COPYs docs/business/services/ at image build time, so the
new manifest + training markdown ship into the image automatically. Verify
with:
  docker exec cib7-poc-mcp ls /app/services-spec/<service>/build/
  curl http://localhost:3000/.well-known/mcp/services.json

When the flow works end-to-end, commit the spec and generated files together:
  git add docs/business/services/<service>/ \
          docs/business/services/build/services.json \
          cib7/src/main/resources/processes/ \
          cib7/src/main/resources/templates/ \
          frontend/src/forms/
  git commit -m "<service>: <one-line summary>"
```

Don't run `docker compose` or `git commit` from the skill — those stay with
the human.
