# Co-signing: `founder`

Every co-founder the applicant lists must sign the Articles of Association
through a link in their email before the case goes to the Business Register.
The page is the SPA's `/sign-founder/{token}`; its API is the backend's
generic co-signing endpoint `/api/public/consent/founder/{token}`.

**Purpose:** `founder` (the capability token's purpose and the URL segment)
**Process:** `businessRegistration`
**Applicant name variables:** `applicantFirstName`, `applicantLastName`

## Variables

| Role | Variable |
|---|---|
| parties | `additionalFounders` |
| confirmations | `founderSignatures` |
| rejected | `rejectedByFounder` |
| sent | `sentToRegister` |

## Messages

| Role | BPMN message |
|---|---|
| signature | `FounderSignature` |
| send | `SubmitToRegister` |

## Wording

| Key | Text |
|---|---|
| party | Co-founder |
| rejection | rejected the registration |
| unknownLink | This signing link is unknown or has expired. |
| alreadyRejected | Another co-founder has already rejected this registration. |
| alreadySent | This case has already been submitted to the Business Register. |
| alreadySigned | You have already signed this registration. |
| notWaiting | This case is no longer waiting for co-founder signatures. |
| rejectedBack | This case was rejected by a co-founder and is back with the applicant. |
| notReady | Not all co-founders have signed yet. |
| notWaitingForSend | This case is not waiting on a submit-to-register signal. |

## Shown to the co-founder

What the co-founder is signing, and nothing more. Board members appear by
name only: their personal codes stay off this unauthenticated page.

| Detail | Variable | Type |
|---|---|---|
| `companyName` | `companyName` | string |
| `shareCapital` | `shareCapital` | number |
| `boardMembers` | `boardMembers` | names |

| Document | Id variable | Category |
|---|---|---|
| `articles` | `aoaDocumentAttachmentId` | `founder-articles-of-association` |
