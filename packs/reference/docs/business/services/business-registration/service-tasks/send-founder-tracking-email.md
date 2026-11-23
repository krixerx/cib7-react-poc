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
| URL | `${busBaseUrl}/api/v1/send` |
| Headers | `Content-Type: application/json` |

Downstream: Mailpit (the bus routes `/api/v1/send`).

## Payload (request body)

```
payload-template: founder-tracking-email.json.ftl
```

**Document:** `documents/email/business-founder-tracking.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  Mailpit /api/v1/send payload for the "Send applicant tracking email"
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
  "From": { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "${("Your OÜ registration is awaiting co-founder signatures: " + (companyName!""))?json_string}",
  "Text": "${documents.text("business-founder-tracking", execution)?json_string}"
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

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Sent once, when the case has co-founders, before the signing subprocess starts.
- The link carries a capability token from `links.consent(execution, "founder", "applicant")` (docs/security.md rule 3).
