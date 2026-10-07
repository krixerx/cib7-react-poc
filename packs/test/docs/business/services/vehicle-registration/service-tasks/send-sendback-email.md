# Service task: `send-sendback-email`

**Task id:** `send-sendback-email`
**BPMN task id:** `Task_SendBackEmail`
**Display name:** `Send "sent back" email`
**Connector:** `http-connector`
**Async-before:** `true`

Tells the applicant the registration came back, with the reason, so they can correct and resubmit.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/v1/send` |
| Headers | `Content-Type: application/json` |

Downstream: Mailpit (the bus routes `/api/v1/send`).

## Payload (request body)

```
payload-template: vehicle-sendback-email.json.ftl
```

**Document:** `documents/email/vehicle-sendback.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  Mailpit /api/v1/send payload for Task_SendBackEmail in
  vehicle-registration.bpmn: tells the applicant the case came back, after a
  reviewer's send-back or a co-owner's rejection (both write sendBackReason).
  The body is the pack document documents/email/vehicle-sendback.ftl.

  Same recipient rule as the business send-back email: the applicant's email
  from the account, else the initiator's demo address.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${toEmail?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your vehicle registration needs corrections",
  "Text": "${documents.text("vehicle-sendback", execution)?json_string}"
}
```

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Reached from a reviewer's send-back (`Gateway_Decision`) and from a co-owner's rejection (`Gateway_AllConfirmed`); both write `sendBackReason`.
- It used to be inline JSON in the BPMN, where the reviewer's free-text reason went in unescaped: a quote or a line break in the reason made the payload invalid and stopped the case with an incident. It is a template with `?json_string` now.
