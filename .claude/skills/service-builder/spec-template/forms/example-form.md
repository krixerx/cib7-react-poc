<!--
  Form spec — one file per BPMN user task. Filename = form id (kebab-case).
  The service-builder skill reads this file to emit:
    frontend/src/forms/<form-id>/<PascalCase>Form.tsx
  …and a registry entry in frontend/src/forms/registry.ts.

  The corresponding BPMN user task gets camunda:formKey="react:<form-id>".

  Replace ALL `<…>` placeholders. Delete sections that don't apply.
-->

# Form: `<form-id>`

**Form id:** `<form-id>` (kebab-case, globally unique)
**BPMN task:** `<task-id>` (the user task this form is mounted on)
**Audience:** `<initiator>` | `<group-name>` (e.g. `civil-servant`)
**Mode:** `entry` (collects new data) | `review` (read-only with action buttons)

## Intro

<One short sentence shown above the form. Two variants if the form is on a
send-back loop:
  first-submit:   "Applicant form — fill in the details, then confirm."
  resubmission:   "Update the details below and resubmit the application." >

## Fields

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `<varName>` | `<UI label>` | `text` | yes | `data.<varName>` | non-empty |
| `<varName>` | `<UI label>` | `number` | yes | `data.<varName>` | integer 1..130 |
| `<varName>` | `<UI label>` | `email` | no | `data.<varName>` | email or empty |
| `<varName>` | `<UI label>` | `select` from `<source>` | yes | `data.<varName>` | one of a, b, c |
| `<varName>` | `<UI label>` | `textarea` | no | `data.<varName>` | — |

The Validation column is not free text: the engine enforces it on every
client (SPA, MCP agent, direct REST call) through a generated value schema.
Use only the phrases in SKILL.md step 10a (`non-empty`, `non-empty, max N
chars`, `integer A..B`, `number >= N`, `one of a, b, c`, `email`, `email or
empty`, `personal code (EE)`, `vehicle VIN`, `list of contacts`, `list of {f1, f2}, min N`,
`pending upload or null`, `cleared to ""`, `identity`). UI-only behaviour
(trimming, auto-suffixes) goes in Notes.

## Conditional rules (optional)

Rules that depend on another field. Each becomes an `if`/`then` in the
value schema.

| When | Then |
|---|---|
| `<varName>` is a non-empty list | `<otherVar>` is `email` |
| `decision` is `sendback` | `sendBackReason` is `non-empty` |

Input types: `text`, `number`, `email`, `password`, `textarea`, `checkbox`,
`select` (specify the data source — typically a function from
`frontend/src/api/`), `date`.

## Actions

One row per submit button. `complete-with` is the typed-variables payload
sent to `onComplete`. Camunda types: `String`, `Integer`, `Long`, `Double`,
`Boolean`, `Date`, `Json`.

| Button label | When enabled | complete-with |
|---|---|---|
| `Confirm` | not submitting | `<varName>:String, <varName>:Integer, …` |
| `Accept` | not submitting | `decision="approve":String` |
| `Send back…` | reason non-empty | `decision="sendback":String, sendBackReason:String` |

## Send-back loop (optional)

Set `on-send-back: clear` (default) if this form is the target of a send-back
loop. The form reads `sendBackReason` from `data`, shows it as a yellow
banner above the form, and clears it on the next submit (so a future cycle
doesn't show a stale reason).

`on-send-back:` `clear` | `keep` | `n/a`

## Read-only mode

When `readOnly` is true (process is finished), every input is `disabled`
and the action row is hidden. Field defaults still apply so the data is
visible.

## Notes

<Free-form context for the developer: edge cases, gotchas, why a particular
validation was chosen. The service-builder may include short excerpts as
code comments — keep them factual.>
