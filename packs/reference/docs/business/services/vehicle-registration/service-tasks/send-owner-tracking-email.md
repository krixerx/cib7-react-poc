# Service task: `send-owner-tracking-email`

**Task id:** `send-owner-tracking-email`
**BPMN task id:** `Task_SendApplicantTrackingEmail`
**Display name:** `Send owner tracking email`
**Connector:** `http-connector`
**Async-before:** `true`

Tells the applicant that the registration waits for co-owner signatures and gives them their own tracking link.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: applicant-tracking-email.json.ftl
```

**Document:** `documents/email/vehicle-applicant-tracking.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  mail-send payload (Patchbay integration, v1) for the "Send owner tracking email"
  service task in vehicle-registration.bpmn.

  Sent once at the start of the co-owner signing phase. The owner is
  pre-confirmed by the form submission, so the link mainly
  exists so the owner sees the same page as every other co-owner and
  can click "Send to Transport Authority" once the round of signatures
  completes.

  Scope: process variables firstName, lastName, applicantEmail,
  additionalOwners; reserved beans frontendBaseUrl and links. The token is
  minted by links.consent(execution, "owner", "applicant") and never stored
  (docs/security.md rule 3).

  The body is the pack document documents/email/vehicle-applicant-tracking.ftl.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "${(applicantEmail!"")?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "Your vehicle registration is awaiting co-owner signatures",
  "text": "${documents.text("vehicle-applicant-tracking", execution)?json_string}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "firstName": "Ants",
  "lastName": "Avaldaja",
  "applicantEmail": "ants@example.com",
  "additionalOwners": [
    {
      "name": "Olga Omanik",
      "email": "olga@example.com",
      "partyId": "p1"
    }
  ],
  "owner": {
    "name": "Olga Omanik",
    "email": "olga@example.com",
    "partyId": "p1"
  }
}
```

| Path | Expected |
|---|---|
| `/from/email` | `"process@cib7-poc.local"` |
| `/to/0/email` | `"ants@example.com"` |
| `/text` | `matches "/consent/owner/[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+"` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Sent once, when the case has co-owners, before the signing subprocess starts.
- The link carries a capability token from `links.consent(execution, "owner", "applicant")`, minted server-side and bound to the case, party and round (docs/security.md rule 3).
