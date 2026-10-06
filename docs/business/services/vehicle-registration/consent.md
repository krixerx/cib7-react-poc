# Co-signing: `owner`

Every co-owner the applicant lists must confirm the registration through a
link in their email before the case reaches Transport Authority. The page is
the SPA's `/confirm-owner/{token}`; its API is the backend's generic
co-signing endpoint `/api/public/consent/owner/{token}`.

**Purpose:** `owner` (the capability token's purpose and the URL segment)
**Process:** `vehicleRegistration`
**Applicant name variables:** `firstName`, `lastName`

## Variables

| Role | Variable |
|---|---|
| parties | `additionalOwners` |
| confirmations | `ownerConfirmations` |
| rejected | `rejectedByOwner` |
| sent | `sentToProcess` |

## Messages

| Role | BPMN message |
|---|---|
| signature | `OwnerConfirmation` |
| send | `SendToProcess` |

## Wording

| Key | Text |
|---|---|
| party | Owner |
| rejection | rejected the application |
| unknownLink | This confirmation link is unknown or has expired. |
| alreadyRejected | Another owner has already rejected this application. |
| alreadySent | This case has already been sent to the back office. |
| alreadySigned | You have already signed this application. |
| notWaiting | This case is no longer waiting for owner signatures. |
| rejectedBack | This case was rejected by an owner and is back with the applicant. |
| notReady | Not all owners have signed yet. |
| notWaitingForSend | This case is not waiting on a send-to-process signal. |

## Shown to the co-owner

Nothing beyond the parties and their states.
