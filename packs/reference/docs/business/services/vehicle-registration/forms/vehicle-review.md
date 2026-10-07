# Form: `vehicle-review`

**Form id:** `vehicle-review` (kebab-case, globally unique)
**BPMN task:** `Task_Review`
**Audience:** `civil-servant`
**Mode:** `review` (read-only data display with two action buttons)
**Renderer:** `schema` (JSON definition drawn by the core form renderer)
**Texts:** i18n namespace `vehicle-review`

## Intro

| When | Text key | English |
|---|---|---|
| editing | `intro.edit` | Transport Authority review. Check the owner details and the vehicle value from the registry. Accept the registration, or send it back to the owner with a reason. |
| read-only | `intro.readOnly` | A read-only view of the owner details, the vehicle value from the registry, and the reviewer's decision. |

## Summary

Read-only `label: value` rows above the fields.

| Label key | Variable | Format | Shown |
|---|---|---|---|
| `summary.firstName` | `firstName` | text | always |
| `summary.lastName` | `lastName` | text | always |
| `summary.age` | `age` | text | always |
| `summary.decision` | `decision` | decision | read-only |
| `summary.sendBackReason` | `sendBackReason` | text | read-only |

## Fields

| Field name | UI label key | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `price` | `fields.vehicleValue.label` | `display`, format currency | n/a | `data.price` | — |
| `decision` | — (set by the action buttons) | hidden | yes | — | one of approve, sendback |
| `sendBackReason` | `fields.sendBackReason.label`, placeholder `fields.sendBackReason.placeholder` | `textarea` (3 rows), revealed by `sendback` | only when sending back (message `errors.reasonRequired`) | `data.sendBackReason` | — |

## Notices

| Label key | Variable | Shown |
|---|---|---|
| `previousReason` | `sendBackReason` | editing |

## Conditional rules

| When | Then |
|---|---|
| `decision` is `sendback` | `sendBackReason` is `non-empty` |

## Actions

| Id | Button label key | Style | Confirm label key | complete-with |
|---|---|---|---|---|
| `approve` | `actions.accept` | primary | — | `decision="approve":String` |
| `sendback` | `actions.sendBackEllipsis` | danger | `actions.confirmSendBack` | `decision="sendback":String, sendBackReason:String` (from the field) |

While a completion is in flight every button shows `actions.working`.

## Send-back loop

`on-send-back: n/a` — this form is the source of the send-back loop. The
reason it writes is shown on the owner's `owner-vehicle` form in the next
round.

## Read-only mode

When `readOnly` is true, the action row and the reason textarea are hidden;
the decision and the reason appear in the summary.

## Submission examples

Run against the form's value schema (the one the engine checks every
completion with) by the core's pack checks (`SubmissionExamplesTest`). A
change replaces the named fields of the submission above.

```json
{"decision": "approve"}
```

| Change | Result | Why |
|---|---|---|
| `{}` | accepted | approve needs nothing else |
| `{"decision": "sendback", "sendBackReason": "fix it"}` | accepted | a send-back with a reason |
| `{"decision": "sendback", "sendBackReason": " "}` | refused | the reason may not be blank |
| `{"decision": "sendback"}` | refused | a send-back needs a reason |
| `{"decision": "reject"}` | refused | approve or sendback only |

## Notes

- `price` is written by the vehicle registry lookup (`Task_GetPrice`), not
  by a client.
