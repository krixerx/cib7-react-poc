# Form: `business-details`

**Form id:** `business-details` (kebab-case, globally unique)
**BPMN task:** `Task_SubmitBusinessDetails`
**Audience:** `initiator`
**Mode:** `entry` (collects new data; supports send-back resubmit too)
**Renderer:** `tsx` until migrated to a form definition (core/pack split, task S44)

## Intro

First-submit: "Register a new private limited company (OÜ). Fill in the
company details and at least one board member, then submit."
Resubmission: "Your registration was sent back for corrections. Update the
details below and resubmit."

## Fields

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `companyName` | `Company name` | `text` | yes | `data.companyName` | non-empty, max 200 chars |
| `boardMembers` | `Board members` | `repeating-rows` of {`firstName`, `lastName`, `personalCode`} | yes | `data.boardMembers` (Json) | list of {firstName, lastName, personalCode}, min 1 — names `non-empty`, personalCode `personal code (EE)` |
| `shareCapital` | `Share capital (EUR)` | `number` | yes | `data.shareCapital` | number >= 2500 |
| `applicantFirstName` | `Your first name` | `text` (from account) | yes | `data.applicantFirstName` | identity |
| `applicantLastName` | `Your last name` | `text` (from account) | yes | `data.applicantLastName` | identity |
| `applicantAge` | `Your age` | `number` | yes | `data.applicantAge` | integer 0..130 |
| `applicantResidency` | `Your residency status` | `radio` (citizen, e-resident, foreign) | no (form: yes) | `data.applicantResidency` | one of citizen, e-resident, foreign |
| `applicantEmail` | `Your email (required if you list co-founders)` | `email` (from account) | no | `data.applicantEmail` | identity |
| `pendingAoaDocument` | `Articles of Association (required)` | `file` (PDF, JPEG, PNG, max 10 MB) | no (form: yes) | `data.aoaDocumentAttachmentId` | pending upload or null |
| `additionalFounders` | `Co-founders` | `repeating-rows` of {`name`, `email`} | no | `data.additionalFounders` (Json) | list of contacts |
| `sendBackReason` | — (not shown; cleared on submit) | hidden | no | — | cleared to "" |

## Conditional rules

| When | Then |
|---|---|
| `additionalFounders` is a non-empty list | `applicantEmail` is `email` |

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Submit` / `Resubmit` | not submitting, all required fields valid | `companyName:String, boardMembers:Json, shareCapital:Double, applicantFirstName:String, applicantLastName:String, applicantAge:Integer, applicantResidency:String, applicantEmail:String, sendBackReason="":String, additionalFounders:Json, pendingAoaDocument:Json` |

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

- Required `no (form: yes)`: the SPA always sends the field, but the MCP
  agent flow does not collect it yet (task S43), so the engine checks its
  value only when present. Make it `yes` once the MCP schema sends it.
- `companyName` is trimmed and gets " OÜ" appended on blur when the user
  leaves it out (UI behaviour, not a value rule).
- `pendingAoaDocument` is `{pendingKey, filename, contentType}` for a fresh
  upload and `null` when the document from an earlier round is kept; the
  form always writes it so a stale value cannot re-run the attach task.
- The form also refuses duplicate co-founder emails and the applicant's own
  email in the co-founder list. Those two rules are form-only: the value
  schema cannot express them.
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
- Co-founders (`additionalFounders`) are written as plain `{name, email}`
  rows. The form never creates link tokens, party ids or the
  `founderSignatures` map, and does not reset `rejectedByFounder` /
  `sentToRegister`: the engine's `ConsentPartiesListener` rebuilds all of
  that on every submit and ignores anything else the client sends
  (docs/security.md rule 3).
