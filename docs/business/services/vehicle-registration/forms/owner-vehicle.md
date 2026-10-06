# Form: `owner-vehicle`

**Form id:** `owner-vehicle` (kebab-case, globally unique)
**BPMN task:** `Task_SubmitDetails`
**Audience:** `initiator`
**Mode:** `entry` (collects new data; supports send-back resubmit too)

## Intro

First-submit: "Owner form – fill in your details, choose the vehicle you are
registering, and list any co-owners that must sign before the case goes to
Transport Authority."
Resubmission: "Update the details below and resubmit the registration. New
signing links will be sent to every co-owner."

## Fields

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `firstName` | `First name` | `text` (read-only, from account) | yes | `data.firstName` | identity |
| `lastName` | `Last name` | `text` (read-only, from account) | yes | `data.lastName` | identity |
| `age` | `Age` | `number` | yes | `data.age` | integer 1..130 |
| `applicantEmail` | `Email (required if you list co-owners)` | `email` (from account) | no | `data.applicantEmail` | identity |
| `pendingIdDocument` | `Owner ID document (required)` | `file` (PDF, JPEG, PNG, max 10 MB) | yes | `data.idDocumentAttachmentId` | pending upload or null |
| `objectId` | `Vehicle (from registry)` | `select` from `listVehicles()` (`frontend/src/api/vehicleRegistryApi.ts`) | yes | `data.objectId` | non-empty |
| `additionalOwners` | `Co-owners` | `repeating-rows` of {`name`, `email`} | no | `data.additionalOwners` (Json) | list of contacts |
| `sendBackReason` | — (not shown; cleared on submit) | hidden | no | — | cleared to "" |

## Conditional rules

| When | Then |
|---|---|
| `additionalOwners` is a non-empty list | `applicantEmail` is `email` |

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Confirm` (first submit) / `Resubmit` | not submitting, all required fields valid | `firstName:String, lastName:String, age:Integer, objectId:String, applicantEmail:String, sendBackReason="":String, additionalOwners:Json, pendingIdDocument:Json` |

## Send-back loop

`on-send-back: clear` — the target of the Transport Authority and co-owner
send-back loops. Reads `sendBackReason` from `data`, shows it as a banner
above the form on resubmission, and clears it on the next submit.

## Read-only mode

When `readOnly` is true, every input is `disabled` and the action row is
hidden. Field defaults still apply so the data is visible.

## Notes

- `pendingIdDocument` is `{pendingKey, filename, contentType}` for a fresh
  upload and `null` when the document from an earlier round is kept: the
  form always writes it, because a completion does not clear unlisted
  variables and a stale value would re-run `Task_AttachIdDocument`. The ID
  document itself is still required; on a resubmit it is satisfied by
  `idDocumentAttachmentId` from the previous round.
- Co-owners are written as plain `{name, email}` rows. The engine's
  `ConsentPartiesListener` assigns party ids, records the owner's own
  signature and starts a new consent round (docs/security.md rule 3); the
  form never mints tokens.
- The form also refuses duplicate co-owner emails and the owner's own email
  in the co-owner list. Those two rules are form-only: the value schema
  cannot express them.
- Names and email come from the signed-in Keycloak account;
  `IdentityValidationListener` rejects a changed value on completion.
- Trimming of text fields happens in the form before submit.
