# Service task: `send-business-sendback-email`

**Task id:** `send-business-sendback-email`
**BPMN task id:** `Task_SendBackEmail`
**Display name:** `Send sent-back email`
**Connector:** `http-connector`
**Async-before:** `true`

Tells the applicant the registration came back from the Business Register or a co-founder, with the reason.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: business-sendback-email.json.ftl
```

**Document:** `documents/email/business-sendback.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  mail-send payload (Patchbay integration, v1) for the businessRegistration send-back email.
  Variables in scope: companyName, applicantFirstName, applicantLastName,
  applicantEmail (optional), initiator, sendBackReason, frontendBaseUrl
  (from FrontendConfiguration).

  Recipient: the applicant's own email when they gave one, otherwise the
  initiator-derived demo address — same rule the vehicle process uses. A
  fixed address here would silently swallow every other user's send-backs.

  The body is the pack document documents/email/business-sendback.ftl.
-->
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
{
  "from":    { "email": "process@cib7-poc.local", "name": "Äriregister POC" },
  "to":      [ { "email": "${toEmail?json_string}" } ],
  "subject": "${("OÜ registration sent back for corrections: " + (companyName!""))?json_string}",
  "text":    "${documents.text("business-sendback", execution)?json_string}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "initiator": "lisa",
  "applicantEmail": "ants@example.com",
  "applicantFirstName": "Frida",
  "applicantLastName": "Asutaja",
  "companyName": "Näidis OÜ",
  "sendBackReason": "Please fix the share capital."
}
```

| Path | Expected |
|---|---|
| `/from/email` | `"process@cib7-poc.local"` |
| `/to/0/email` | `"ants@example.com"` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Goes to the applicant's email when it has one, else to the initiator's demo address.
