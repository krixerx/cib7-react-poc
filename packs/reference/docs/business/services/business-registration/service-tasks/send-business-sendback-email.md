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
| URL | `${busBaseUrl}/api/v1/send` |
| Headers | `Content-Type: application/json` |

Downstream: Mailpit (the bus routes `/api/v1/send`).

## Payload (request body)

```
payload-template: business-sendback-email.json.ftl
```

**Document:** `documents/email/business-sendback.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  Mailpit /api/v1/send payload for the businessRegistration send-back email.
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
  "From":    { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To":      [ { "Email": "${toEmail?json_string}" } ],
  "Subject": "${("OÜ registration sent back for corrections: " + (companyName!""))?json_string}",
  "Text":    "${documents.text("business-sendback", execution)?json_string}"
}
```

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Goes to the applicant's email when it has one, else to the initiator's demo address.
