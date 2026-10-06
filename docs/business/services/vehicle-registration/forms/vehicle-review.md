# Form: `vehicle-review`

**Form id:** `vehicle-review` (kebab-case, globally unique)
**BPMN task:** `Task_Review`
**Audience:** `civil-servant`
**Mode:** `review` (read-only data display with two action buttons)

## Intro

"Transport Authority review. Check the owner details and the vehicle value
from the registry. Accept the registration, or send it back to the owner
with a reason."

## Fields

All data inputs are `disabled`; the reviewer only views them. Only the
decision and the send-back reason are written.

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `price` | `Vehicle value (from registry)` | `number` (read-only) | n/a | `data.price` | — |
| `decision` | — (set by the action buttons) | hidden | yes | — | one of approve, sendback |
| `sendBackReason` | `Reason to send back` | `textarea` | only when sending back | `''` | — |

## Conditional rules

| When | Then |
|---|---|
| `decision` is `sendback` | `sendBackReason` is `non-empty` |

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Accept` | not submitting | `decision="approve":String` |
| `Send back…` → `Confirm send back` | reason non-empty AND not submitting | `decision="sendback":String, sendBackReason:String` |

## Send-back loop

`on-send-back: n/a` — this form is the source of the send-back loop. The
reason it writes is shown on the owner's `owner-vehicle` form in the next
round.

## Read-only mode

When `readOnly` is true, the action row and the reason textarea are hidden.

## Notes

- `price` is written by the vehicle registry lookup (`Task_GetPrice`), not
  by a client.
