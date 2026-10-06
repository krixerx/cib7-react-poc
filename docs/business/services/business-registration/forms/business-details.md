# Form: `business-details`

**Form id:** `business-details` (kebab-case, globally unique)
**BPMN task:** `Task_SubmitBusinessDetails`
**Audience:** `initiator`
**Mode:** `entry` (collects new data; supports send-back resubmit too)
**Renderer:** `schema` (JSON definition drawn by the core form renderer)
**Texts:** i18n namespace `business-details`

## Intro

| When | Text key | English |
|---|---|---|
| editing | `intro.default` | Register a new Estonian private limited company (OÜ). Fill in the company name, at least one board member, the share capital, your own details, and list any co-founders that must sign the Articles of Association before the case goes to the Business Register. |
| resubmission | `intro.resubmission` | Update the founding details below and resubmit to the Business Register. New signing links will be sent to every co-founder. |
| read-only | `intro.readOnly` | A read-only view of the submitted OÜ founding details. |

**Resubmission:** while `sendBackReason` is set, the form shows the banner
`banner.sentBack` with the reason, the resubmission intro and the
`actions.resubmit` button label.

## Fields

| Field name | UI label key | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `companyName` | `fields.companyName.label`, placeholder `fields.companyName.placeholder` | `text`, suffix `OÜ` (appended on submit when missing) | yes (message `errors.companyNameRequired`) | `data.companyName` | non-empty, max 200 chars |
| `boardMembers` | legend `sections.boardMembers.legend` | `rows` of `firstName` (placeholder `fields.boardMember.firstNamePlaceholder`), `lastName` (`fields.boardMember.lastNamePlaceholder`), `personalCode` (`fields.boardMember.personalCodePlaceholder`, personal code (EE), fixed length 11: underscores show the digits still missing); at least 1 row shown; add `sections.boardMembers.add`, remove aria `sections.boardMembers.removeAria` | yes (none: `errors.boardMemberRequired`; a gap: `errors.boardMemberIncomplete`; bad code: `errors.personalCodeFormat`) | `data.boardMembers` (Json) | list of {firstName, lastName, personalCode}, min 1 — names `non-empty`, personalCode `personal code (EE)` |
| `pendingAoaDocument` | `fields.aoa.label`, hint `fields.aoa.hint` | `file`: category `founder-articles-of-association`, PDF/JPEG/PNG, max 10 MB, drop label `fields.aoa.uploadLabel`; kept upload from `aoaDocumentAttachmentId` named `fields.aoa.existingFilename` | no (form: yes, message `errors.aoaRequired`) | `data.aoaDocumentAttachmentId` | pending upload or null |
| `shareCapital` | `fields.shareCapital.label` | `number`, decimal, min 2500, step 100, starts at 2500 | yes (message `errors.shareCapitalMin`, also below 2500) | `data.shareCapital` | number >= 2500 |
| `applicantFirstName` | `fields.applicantFirstName.label` | `text`, identity (from account) | yes (message `errors.applicantNameRequired`) | `data.applicantFirstName` | identity |
| `applicantLastName` | `fields.applicantLastName.label` | `text`, identity (from account) | yes (message `errors.applicantNameRequired`) | `data.applicantLastName` | identity |
| `applicantAge` | `fields.applicantAge.label` | `number` 0..130 | yes (message `errors.applicantAgeRange`, also when out of range) | `data.applicantAge` | integer 0..130 |
| `applicantResidency` | legend `fields.residency.legend`, hint `fields.residency.hint` | `radio`: `citizen` (`fields.residency.options.citizen.label` / `.hint`), `e-resident` (`…eResident.…`), `foreign` (`…foreign.…`); starts at `citizen` | no (form: yes, always set) | `data.applicantResidency` | one of citizen, e-resident, foreign |
| `applicantEmail` | `fields.applicantEmail.label`, placeholder `fields.applicantEmail.placeholder` | `email`, identity (from account) | no (invalid: `errors.emailInvalid`) | `data.applicantEmail` | identity |
| `additionalFounders` | legend `sections.coFounders.legend` (with count), hint `sections.coFounders.hint` | `contacts`: placeholders `fields.founder.namePlaceholder` / `fields.founder.emailPlaceholder`, add `sections.coFounders.add`, remove aria `sections.coFounders.removeAria` | no | `data.additionalFounders` (Json) | list of contacts |
| `sendBackReason` | — (not shown; cleared on submit) | hidden | no | — | cleared to "" |

Contact row messages: name missing `errors.founderNameRequired`; email
invalid `errors.founderEmailInvalid`; repeated email
`errors.duplicateFounderEmail`; the applicant's own email in the list
`errors.applicantEmailInFounders`.

## Conditional rules

| When | Then |
|---|---|
| `additionalFounders` is a non-empty list | `applicantEmail` is `email` (message `errors.applicantEmailRequiredForFounders`) |

## Actions

| Id | Button label key | Resubmit label key | Style | complete-with |
|---|---|---|---|---|
| `submit` | `common:actions.submit` | `actions.resubmit` | primary | `companyName:String, boardMembers:Json, shareCapital:Double, applicantFirstName:String, applicantLastName:String, applicantAge:Integer, applicantResidency:String, applicantEmail:String, sendBackReason="":String, additionalFounders:Json, pendingAoaDocument:Json` |

While a completion is in flight the button shows `common:feedback.submitting`.

## Send-back loop

`on-send-back: clear` — this form is the target of the civil-servant
send-back loop. Shows `sendBackReason` in the banner on resubmission and
clears it on the next submit (so a future cycle doesn't show a stale
reason).

## Read-only mode

When `readOnly` is true (process is finished), every input is `disabled`
and the action row is hidden. Field defaults still apply so the data is
visible.

## Notes

- Required `no (form: yes)`: the form insists, but the MCP agent flow does
  not collect the field yet (task S43), so the engine checks its value only
  when present. Make it `yes` once the MCP schema sends it.
- `companyName` is trimmed and gets " OÜ" appended on submit when the user
  leaves it out; the LLM training markdown also tells the agent to ask
  first, but the form still produces a valid name.
- `pendingAoaDocument` is `{pendingKey, filename, contentType}` for a fresh
  upload and `null` when the document from an earlier round is kept; the
  form always writes it so a stale value cannot re-run the attach task.
- The duplicate and own-email checks for co-founders are form-only: the
  value schema cannot express them.
- `boardMembers` always shows at least one row; removing the last one
  leaves an empty row. Rows that are entirely empty are dropped on submit.
  It is serialised as JSON (Camunda `Json` type) so it survives history
  persistence and stays queryable via `query_user_history`.
- Personal code is the 11-digit Estonian ID code (`isikukood`). The form
  checks the format only; checksum validation is out of scope for the POC.
- Co-founders (`additionalFounders`) are written as plain `{name, email}`
  rows. The form never creates link tokens, party ids or the
  `founderSignatures` map, and does not reset `rejectedByFounder` /
  `sentToRegister`: the engine's `ConsentPartiesListener` rebuilds all of
  that on every submit and ignores anything else the client sends
  (docs/security.md rule 3).
