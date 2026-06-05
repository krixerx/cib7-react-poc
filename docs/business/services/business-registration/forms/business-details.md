# Form: `business-details`

**Form id:** `business-details` (kebab-case, globally unique)
**BPMN task:** `Task_SubmitBusinessDetails`
**Audience:** `initiator`
**Mode:** `entry` (collects new data; supports send-back resubmit too)

## Intro

First-submit: "Register a new private limited company (OÜ). Fill in the
company details and at least one board member, then submit."
Resubmission: "Your registration was sent back for corrections. Update the
details below and resubmit."

## Fields

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `companyName` | `Company name (OÜ)` | `text` | yes | `data.companyName` | non-empty, trim, must end in " OÜ" — append " OÜ" if missing on blur |
| `boardMembers` | `Board members` | `repeating-rows` of {`firstName`, `lastName`, `personalCode`} | yes (min 1) | `data.boardMembers` (Json) | each row non-empty; personalCode 11 digits |
| `shareCapital` | `Share capital (EUR)` | `number` | yes | `data.shareCapital` | integer >= 2500 |
| `applicantFirstName` | `Your first name` | `text` | yes | `data.applicantFirstName` | non-empty, trim |
| `applicantLastName` | `Your last name` | `text` | yes | `data.applicantLastName` | non-empty, trim |
| `applicantAge` | `Your age` | `number` | yes | `data.applicantAge` | integer 0..130 |

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Submit` | not submitting, all required fields valid | `companyName:String, boardMembers:Json, shareCapital:Double, applicantFirstName:String, applicantLastName:String, applicantAge:Integer, sendBackReason="":String` |

## Send-back loop

`on-send-back: clear` — this form is the target of the civil-servant
send-back loop. Reads `sendBackReason` from `data`, shows it as a yellow
banner above the form on resubmission, and clears it on the next submit
(so a future cycle doesn't show a stale reason).

## Read-only mode

When `readOnly` is true (process is finished), every input is `disabled`
and the action row is hidden. Field defaults still apply so the data is
visible.

## Notes

- `boardMembers` is a list of `{firstName, lastName, personalCode}` rendered
  as repeating rows with an "Add member" button. At least one row required
  (the form enforces it; if the user removes the last row, "Add member"
  silently re-adds a blank row).
- Personal code is the 11-digit Estonian ID code (`isikukood`). The form
  enforces length only; checksum validation is out of scope for the POC.
- The `companyName` field auto-appends " OÜ" if the user submits without
  it — the LLM training markdown also tells the agent to ask first, but
  the form's defense-in-depth means a careless submission still produces a
  valid name.
- `boardMembers` is serialised as JSON on submit (Camunda `Json` type) so
  it survives history persistence and stays queryable via
  `query_user_history`.
