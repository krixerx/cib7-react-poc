<!--
  Service spec — the single source of truth for one business service.
  Copy this folder to packs/reference/docs/business/services/<service-id>/ and edit every
  section. The service-builder skill reads this file to generate the BPMN
  and to rewrite the mermaid block below.

  Replace ALL `<…>` placeholders. Leave the bpmn-diagram markers untouched —
  scripts/bpmn-to-mermaid.mjs fills the block in.
-->

# <Service Display Name>

**Status:** draft
**Process key:** `<processKeyCamelCase>`
**BPMN:** [`packs/reference/engine/processes/<service-id>/<service-id>.bpmn`](../../../../engine/processes/<service-id>/<service-id>.bpmn)

**When to read this:** before changing the <service-id> flow, its forms, or
its integrations. Cross-cutting topics live in
[`docs/architecture.md`](../../../../../../docs/architecture.md),
[`docs/cib7.md`](../../../../../../docs/cib7.md),
[`docs/frontend.md`](../../../../../../docs/frontend.md), and
[`docs/human-role-react-forms-spec.md`](../../../../../../docs/human-role-react-forms-spec.md).

## Catalog

How the services page and the payment page present the service. Generated
into the pack's `frontend/catalog.json` and `frontend/locales/<lang>/catalog.json`.

| Item | Value |
|---|---|
| Category | `<business | family | property | travel | social | other>` |
| Summary | en: <one line under the service name> · ar: <the same in Arabic> |
| Fee name | en: <the state fee's name on the payment page> · ar: <…> |
| Issuer | `<issuer-id>`, tone `<primary | ok>`: name <authority name>; sub en: <…> · ar: <…> |

Display names (process, task and activity `name=` in the BPMN) are
translated in the pack's `names` namespace; see the service-builder
skill §8.1.

## What this service does

<One short paragraph. Who starts it, what happens, when it ends. Plain language —
no BPMN jargon. This paragraph also feeds the LLM training markdown the
service-builder generates for the MCP sidecar — write it for both audiences.>

## Flow

<!--
  Describe every node and edge of the process. The service-builder reads this
  section to emit BPMN. Use the following micro-syntax for each line:

    start              <label>                                  initiator=<varName>
    user-task          <task-id> "<display name>"               form=<form-id> role=initiator
    user-task          <task-id> "<display name>"               form=<form-id> group=<group>
    service-task       <task-id> "<display name>"               (see service-tasks/<task-id>.md)
    business-rule-task <task-id> "<display name>"               decision=<decision-id> result=<varName>
    gateway-exclusive  <gateway-id> "<question?>"               default=<branch-id>
    boundary-timer     <event-id> "<label>" attached-to=<task-id> non-interrupting cycle=R/PT2M
    end                <end-id> "<label>"
    flow               <source-id> -> <target-id>               label="<flow label>"
    flow               <source-id> -> <target-id>               label="<flow label>" if=${<juel-expr>}

  Order doesn't drive emission — the builder topologically sorts by `flow`
  edges. List nodes once and flows once. Use kebab-case ids throughout.
-->

```
<paste the node + flow lines here>
```

## Forms

| Form id | BPMN task | Audience | Spec |
|---|---|---|---|
| `<form-id>` | `<task-id>` | initiator / `<group>` | [`forms/<form-id>.md`](forms/<form-id>.md) |

## Service tasks

| BPMN task | Kind | Spec |
|---|---|---|
| `<task-id>` | http-connector | [`service-tasks/<task-id>.md`](service-tasks/<task-id>.md) |

## Decisions

| BPMN task | DMN | Spec |
|---|---|---|
| `<task-id>` | `<decision-id>` | [`decisions/<decision-id>.md`](decisions/<decision-id>.md) |

## Process variables

| Variable | Set by | Type | Notes |
|---|---|---|---|
| `initiator` | start event | String | Login of the user that started the case. |
| `<varName>` | `<task-id>` | `<String\|Integer\|Long\|Double\|Boolean\|byte[]>` | <Optional notes. byte[] for anything > 4 kB.> |

## Documents

| Category | By | Label (en / ar) |
|---|---|---|
| `<kebab-name>` | applicant | <label> / <Arabic label> |
| `generated-<kebab-name>` | system | <label> / <Arabic label> |

`by: applicant` = a signed-in user may upload it; `by: system` = only the
engine files it (rendered PDFs), and its name starts with `generated-`. The
issued certificate is the core's `generated-certificate`; do not declare it.

## State fee (optional, only for a service with a payment step)

Generated into the pack's `backend/payment/<service>.yaml`. Add a
`quote-…` service task (GET `${busBaseUrl}/api/internal/payments/quote/${execution.processInstanceId}`,
output `stateFee`) before the invoice, and print `stateFee` in it.

| Item | Value |
|---|---|
| Fee name | <shown on the payment page> |
| Recipient | <the authority that receives it> |
| Currency | <ISO code, e.g. EUR> |
| Amount | <`N flat`, or by `<variable>`: below L1: A1; below L2: A2; otherwise A3> |

The tier variable must be one the engine sets (a connector output), never
one a client writes.

## Variable write policy

The variables a client (SPA, MCP agent) may write, per start and per form.
`/service-builder` generates
`packs/reference/engine/processes/<service>/variable-policy.json` from this
table and `VariableWritePolicyFilter` refuses anything else with 403
(docs/security.md rule 2). Everything not listed here is system-owned.

| Start / form | Client may write | Notes |
|---|---|---|
| start | `<names>` | Same as the MCP start schema; the SPA starts with no variables. |
| `<applicant-form-id>` | `<names>` | From the form's Actions `complete-with`. Identity fields only if `IdentityFieldRegistry` binds them. List SPA-only fields (not in the MCP schema) here. |
| `<review-form-id>` | `decision`, `<reason fields>` | A decision is writable only from the reviewer form that owns it. |

System-owned (never listed above): `initiator`, DMN and connector outputs,
consent and payment state, gateway flags.

## Roles and authorization

- **<Role name>** — Keycloak group `<group>` (engine sees `<group>`, no
  leading slash). Owns `<task-id>` via `<assignee=${initiator} | candidateGroups=<group>>`.

## Known trade-offs

- <List any demo values that should change in production, validation that's
  defense-in-depth only, loops that share a form between first-submit and
  resubmit, etc.>

## LLM guidance (optional)

<This optional section is copied verbatim into the MCP training markdown
generated at `build/mcp-training.md`. Use it when the README's overview is
not enough — examples:

  - "If the applicant gives a share capital below €2500, ask whether they
    meant a non-profit (different process) before retrying."
  - "Status `running` with the review task open is normal — typical 1-2
    business days; do not tell the user the process is stuck."
  - "When autofilling from history, confirm each pre-filled field with the
    user before calling start_process."

Omit the section entirely if the README's overview + variables tables are
sufficient — the service-builder will derive a baseline training markdown
from those alone.>

## Flow diagram

The block below is generated from the BPMN by
[`scripts/bpmn-to-mermaid.mjs`](../../../../../../scripts/bpmn-to-mermaid.mjs).
Do not edit between the markers — the service-builder skill refreshes it on
every run.

<!-- bpmn-diagram:start -->
<!-- bpmn-diagram:end -->
