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
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: vehicle-sendback-email.json.ftl
```

**Document:** `documents/email/vehicle-sendback.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  mail-send payload (Patchbay integration, v1) for Task_SendBackEmail in
  vehicle-registration.bpmn: tells the applicant the case came back, after a
  reviewer's send-back or a co-owner's rejection (both write sendBackReason).
  The body is the pack document documents/email/vehicle-sendback.ftl.

  Same recipient rule as the business send-back email: the applicant's email
  from the account, else the initiator's demo address.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "${toEmail?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "Your vehicle registration needs corrections",
  "text": "${documents.text("vehicle-sendback", execution)?json_string}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "initiator": "lisa",
  "firstName": "Ants",
  "lastName": "Avaldaja",
  "applicantEmail": "ants@example.com",
  "sendBackReason": "Please fix the share capital.",
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

## Example: a hostile reason

```json
{
  "initiator": "lisa",
  "firstName": "Ants",
  "lastName": "Avaldaja",
  "applicantEmail": "ants@example.com",
  "sendBackReason": "Line one,\nthen \"line two\" with a \\ backslash.",
  "owner": {
    "name": "Olga Omanik",
    "email": "olga@example.com",
    "partyId": "p1"
  }
}
```

| Path | Expected |
|---|---|
| `/text` | `contains "Reason: Line one,\nthen \"line two\" with a \\ backslash."` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Reached from a reviewer's send-back (`Gateway_Decision`) and from a co-owner's rejection (`Gateway_AllConfirmed`); both write `sendBackReason`.
- It used to be inline JSON in the BPMN, where the reviewer's free-text reason went in unescaped: a quote or a line break in the reason made the payload invalid and stopped the case with an incident. It is a template with `?json_string` now.
