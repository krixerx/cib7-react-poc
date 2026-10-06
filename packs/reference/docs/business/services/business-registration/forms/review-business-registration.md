# Form: `review-business-registration`

**Form id:** `review-business-registration` (kebab-case, globally unique)
**BPMN task:** `Task_ReviewBusinessRegistration`
**Audience:** `civil-servant`
**Mode:** `review` (read-only data display with two action buttons)
**Renderer:** `schema` (JSON definition drawn by the core form renderer)
**Texts:** i18n namespace `review-business-registration`

## Intro

| When | Text key | English |
|---|---|---|
| editing | `intro.review` | Business Register review. Approve to enter the OÜ in the äriregister; send back with a reason to ask the founder for corrections. |
| read-only | `intro.readOnly` | A read-only view of the submitted OÜ founding details and the reviewer's decision. |

## Summary

| Label key | Shows | Format | Shown |
|---|---|---|---|
| `summary.companyName` | `companyName` | text | always |
| `summary.shareCapital` | `shareCapital` | currency | always |
| `summary.founder` | template `summary.founderValue` over `applicantFirstName`, `applicantLastName`, `applicantAge` ("Frida Asutaja (age 34)") | template | always |
| `summary.residency` | `applicantResidency` as `citizen` → `residency.citizen`, `e-resident` → `residency.eResident`, `foreign` → `residency.foreign` | options | always |
| `summary.boardMembers` | `boardMembers` as a list, each entry `summary.boardMemberItem` ("Bart Simpson (39001010000)") | list | always |
| `summary.decision` | `decision` | decision | read-only |
| `summary.sendBackReason` | `sendBackReason` | text | read-only |

## Fields

| Field name | UI label key | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
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
| `approve` | `common:actions.approve` | primary | — | `decision="approve":String` |
| `sendback` | `actions.openSendBack` | danger | `actions.confirmSendBack` | `decision="sendback":String, sendBackReason:String` (from the field) |

While a completion is in flight every button shows `common:feedback.submitting`.

## Send-back loop

`on-send-back: n/a` — this form is the SOURCE of the send-back loop, not
the target. The reason it writes is consumed by the applicant's
`business-details` form on the next iteration.

## Read-only mode

When `readOnly` is true (process is finished), the action row and the
reason textarea are hidden; the decision and the reason appear in the
summary.

## Notes

- The reviewer is an authenticated civil servant, so board members show
  with their personal codes (11 digits, as entered). The public co-founder
  page shows names only (`consent.md`).
