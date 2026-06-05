# Form: `review-business-registration`

**Form id:** `review-business-registration` (kebab-case, globally unique)
**BPMN task:** `Task_ReviewBusinessRegistration`
**Audience:** `civil-servant`
**Mode:** `review` (read-only data display with two action buttons)

## Intro

"Review the proposed business registration. Approve to register the
company; send back with a reason to ask the applicant for corrections."

## Fields

All inputs are `disabled` — civil servant only views the data. Each row
maps to a process variable from `data`.

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `companyName` | `Company name` | `text` | n/a (read-only) | `data.companyName` | — |
| `boardMembers` | `Board members` | `repeating-rows` read-only | n/a | `data.boardMembers` | — |
| `shareCapital` | `Share capital (EUR)` | `number` | n/a | `data.shareCapital` | — |
| `applicantFirstName` | `Applicant first name` | `text` | n/a | `data.applicantFirstName` | — |
| `applicantLastName` | `Applicant last name` | `text` | n/a | `data.applicantLastName` | — |
| `applicantAge` | `Applicant age` | `number` | n/a | `data.applicantAge` | — |
| `sendBackReason` | `Reason (for send back)` | `textarea` | only when sending back | `''` | non-empty when "Send back..." pressed |

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Approve` | not submitting | `decision="approve":String` |
| `Send back...` | reason non-empty AND not submitting | `decision="sendback":String, sendBackReason:String` |

## Send-back loop

`on-send-back: n/a` — this form is the SOURCE of the send-back loop, not
the target. The reason it writes is consumed by the applicant's
`business-details` form on the next iteration.

## Read-only mode

When `readOnly` is true (process is finished), the action row is hidden
and the reason textarea is removed. Field defaults still apply so the
data is visible.

## Notes

- Mirrors `review-application.tsx` from personRegistration — same overall
  shape, different field set.
- The reason textarea is only relevant when "Send back..." is pressed.
  Keep it visible alongside the action row so the civil servant can write
  the reason before committing.
- Estonian personal codes display as-is (11 digits). No PII redaction in
  the POC.
