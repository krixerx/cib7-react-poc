# Form: `owner-vehicle`

**Form id:** `owner-vehicle` (kebab-case, globally unique)
**BPMN task:** `Task_SubmitDetails`
**Audience:** `initiator`
**Mode:** `entry` (collects new data; supports send-back resubmit too)
**Renderer:** `schema` (JSON definition drawn by the core form renderer)
**Texts:** i18n namespace `owner-vehicle`

## Intro

| When | Text key | English |
|---|---|---|
| editing | `intro.default` | Owner form — fill in your details, choose the vehicle you are registering, and list any co-owners that must sign before the case goes to Transport Authority. |
| resubmission | `intro.resubmission` | Update the details below and resubmit the registration. New signing links will be sent to every co-owner. |
| read-only | `intro.readOnly` | Read-only view of the registration you submitted. Edits are not possible while Transport Authority has the case. |

**Resubmission:** while `sendBackReason` is set, the form shows the banner
`banner.sentBackTitle` with the reason, the resubmission intro and the
`actions.resubmit` button label.

## Fields

| Field name | UI label key | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `firstName` | `fields.firstName.label` | `text`, identity (from account) | yes (message `errors.namesRequired`) | `data.firstName` | identity |
| `lastName` | `fields.lastName.label` | `text`, identity (from account) | yes (message `errors.namesRequired`) | `data.lastName` | identity |
| `age` | `fields.age.label` | `number` 1..130 | yes (message `errors.ageRange`, also when out of range) | `data.age` | integer 1..130 |
| `applicantEmail` | `fields.email.label`, placeholder `fields.email.placeholder` | `email`, identity (from account) | no (invalid: `errors.invalidEmail`) | `data.applicantEmail` | identity |
| `pendingIdDocument` | `fields.idDocument.label`, hint `fields.idDocument.hint` | `file`: category `applicant-id-document`, PDF/JPEG/PNG, max 10 MB, drop label `fields.idDocument.dropLabel`; kept upload from `idDocumentAttachmentId` named `fields.idDocument.existingFilename` | yes (message `errors.idDocumentRequired`) | `data.idDocumentAttachmentId` | pending upload or null |
| `objectId` | `fields.vehicle.label`, placeholder `fields.vehicle.placeholder` | `select` from registry `vehicles`: value `vin`, option `fields.vehicle.option` ("VW Golf 1.4 TSI 2018 · €8,400", `value` in whole euros); load error `errors.registryLoadFailed` | yes (message `errors.vehicleRequired`) | `data.objectId` | vehicle VIN |
| `additionalOwners` | legend `sections.coOwners.legend` (with count), hint `sections.coOwners.hint` | `contacts`: placeholders `fields.coOwnerName.placeholder` / `fields.coOwnerEmail.placeholder`, add `actions.addCoOwner`, remove aria `actions.removeCoOwnerAria` | no | `data.additionalOwners` (Json) | list of contacts |
| `sendBackReason` | — (not shown; cleared on submit) | hidden | no | — | cleared to "" |

Contact row messages: name missing `errors.coOwnerNameRequired`; email
invalid `errors.coOwnerEmailInvalid`; repeated email
`errors.duplicateCoOwnerEmail`; the applicant's own email in the list
`errors.applicantEmailInCoOwners`.

## Conditional rules

| When | Then |
|---|---|
| `additionalOwners` is a non-empty list | `applicantEmail` is `email` (message `errors.applicantEmailRequiredWithCoOwners`) |

## Actions

| Id | Button label key | Resubmit label key | Style | complete-with |
|---|---|---|---|---|
| `submit` | `actions.confirm` | `actions.resubmit` | primary | `firstName:String, lastName:String, age:Integer, objectId:String, applicantEmail:String, sendBackReason="":String, additionalOwners:Json, pendingIdDocument:Json` |

While a completion is in flight the button shows `actions.confirming`.

## Send-back loop

`on-send-back: clear` — the target of the Transport Authority and co-owner
send-back loops. Shows `sendBackReason` in the banner on resubmission and
clears it on the next submit.

## Read-only mode

When `readOnly` is true, every input is `disabled` and the action row is
hidden. Field defaults still apply so the data is visible.

## Submission examples

Run against the form's value schema (the one the engine checks every
completion with) by the core's pack checks (`SubmissionExamplesTest`). A
change replaces the named fields of the submission above.

```json
{"age": 30, "objectId": "WP0AB2A91KS123456", "pendingIdDocument": null, "applicantEmail": "",
 "additionalOwners": [], "sendBackReason": ""}
```

| Change | Result | Why |
|---|---|---|
| `{}` | accepted | the submission above |
| `{"age": 0}` | refused | age is 1 to 130 |
| `{"objectId": " "}` | refused | a VIN is 17 capital letters or digits |
| `{"objectId": "../../internal/documents"}` | refused | the VIN goes into the registry lookup path |
| `{"objectId": "WP0AB2A91KS12345%2F"}` | refused | nothing that could leave the path segment |
| `{"objectId": "wp0ab2a91ks123456"}` | refused | lower case |
| `{"sendBackReason": "x"}` | refused | the applicant only clears the reason |
| `{"pendingIdDocument": {"pendingKey": "k", "filename": "a.exe", "contentType": "application/x-msdownload"}}` | refused | ID document is PDF, JPEG or PNG |
| `{"additionalOwners": [{"name": "Marge", "email": "marge@example.com"}]}` | refused | with co-owners the applicant needs an email |
| `{"additionalOwners": [{"name": "Marge", "email": "marge@example.com"}], "applicantEmail": "bart@example.com"}` | accepted | co-owner and applicant email |
| `{"additionalOwners": [{"name": "Marge", "email": "marge"}], "applicantEmail": "bart@example.com"}` | refused | a co-owner needs a valid email |

## Notes

- `pendingIdDocument` is `{pendingKey, filename, contentType}` for a fresh
  upload and `null` when the document from an earlier round is kept: the
  form always writes it, because a completion does not clear unlisted
  variables and a stale value would re-run `Task_AttachIdDocument`. The ID
  document itself is still required; on a resubmit it is satisfied by
  `idDocumentAttachmentId` from the previous round.
- Co-owners are written as plain `{name, email}` rows, empty rows dropped.
  The engine's `ConsentPartiesListener` assigns party ids, records the
  owner's own signature and starts a new consent round (docs/security.md
  rule 3); the form never mints tokens.
- The duplicate and own-email checks are form-only: the value schema cannot
  express them.
- Names and email come from the signed-in Keycloak account;
  `IdentityValidationListener` rejects a changed value on completion.
- `objectId` is the VIN of a vehicle in the registry and goes into the path of
  the `Task_GetPrice` lookup URL, which is why its rule is the strict `vehicle
  VIN` and not just `non-empty`.
- Text fields are trimmed before submit.
