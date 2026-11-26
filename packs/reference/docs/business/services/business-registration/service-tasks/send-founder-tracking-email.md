# Service task: `send-founder-tracking-email`

**Task id:** `send-founder-tracking-email`
**BPMN task id:** `Task_SendApplicantTrackingEmail`
**Display name:** `Send applicant tracking email`
**Connector:** `http-connector`
**Async-before:** `true`

Tells the applicant that the registration waits for co-founder signatures and gives them their own tracking link.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: founder-tracking-email.json.ftl
```

**Document:** `documents/email/business-founder-tracking.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  mail-send payload (Patchbay integration, v1) for the "Send applicant tracking email"
  service task in business-registration.bpmn.

  Sent once at the start of the co-founder signing phase. The applicant is
  pre-confirmed by the form submission, so the link mainly
  exists so the applicant sees the same page as every other co-founder
  and can click "Submit to register" once the round of signatures
  completes.

  Scope: process variables applicantFirstName, applicantLastName,
  applicantEmail, additionalFounders, companyName; reserved beans
  frontendBaseUrl and links. The token is minted by
  links.consent(execution, "founder", "applicant") and never stored (docs/security.md
  rule 3).

  The body is the pack document documents/email/business-founder-tracking.ftl.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Äriregister POC" },
  "to": [ { "email": "${(applicantEmail!"")?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "${("Your OÜ registration is awaiting co-founder signatures: " + (companyName!""))?json_string}",
  "text": "${documents.text("business-founder-tracking", execution)?json_string}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "applicantEmail": "ants@example.com",
  "applicantFirstName": "Frida",
  "applicantLastName": "Asutaja",
  "companyName": "Näidis OÜ",
  "additionalFounders": [
    {
      "name": "Karl Kaasasutaja",
      "email": "karl@example.com",
      "partyId": "p1"
    }
  ],
  "founder": {
    "name": "Karl Kaasasutaja",
    "email": "karl@example.com",
    "partyId": "p1"
  }
}
```

| Path | Expected |
|---|---|
| `/from/email` | `"process@cib7-poc.local"` |
| `/to/0/email` | `"ants@example.com"` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Sent once, when the case has co-founders, before the signing subprocess starts.
- The link carries a capability token from `links.consent(execution, "founder", "applicant")` (docs/security.md rule 3).
