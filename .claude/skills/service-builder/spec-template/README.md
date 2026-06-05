<!--
  Service spec — the single source of truth for one business service.
  Copy this folder to docs/business/services/<service-id>/ and edit every
  section. The service-builder skill reads this file to generate the BPMN
  and to rewrite the mermaid block below.

  Replace ALL `<…>` placeholders. Leave the bpmn-diagram markers untouched —
  scripts/bpmn-to-mermaid.mjs fills the block in.
-->

# <Service Display Name>

**Status:** draft
**Process key:** `<processKeyCamelCase>`
**BPMN:** [`cib7/src/main/resources/processes/<service-id>.bpmn`](../../../../cib7/src/main/resources/processes/<service-id>.bpmn)

**When to read this:** before changing the <service-id> flow, its forms, or
its integrations. Cross-cutting topics live in
[`../../../architecture.md`](../../../architecture.md),
[`../../../cib7.md`](../../../cib7.md),
[`../../../frontend.md`](../../../frontend.md), and
[`../../../human-role-react-forms-spec.md`](../../../human-role-react-forms-spec.md).

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
[`scripts/bpmn-to-mermaid.mjs`](../../../../scripts/bpmn-to-mermaid.mjs).
Do not edit between the markers — the service-builder skill refreshes it on
every run.

<!-- bpmn-diagram:start -->
<!-- bpmn-diagram:end -->
