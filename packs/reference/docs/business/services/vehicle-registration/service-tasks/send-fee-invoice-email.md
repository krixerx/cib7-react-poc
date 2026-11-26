# Service task: `send-fee-invoice-email`

**Task id:** `send-fee-invoice-email`
**BPMN task id:** `Task_SendApprovalEmail`
**Display name:** `Send state fee invoice email`
**Connector:** `http-connector`
**Async-before:** `true`

Tells the applicant the registration is approved, attaches the state fee invoice and gives the pay link.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: approval-email.json.ftl
```

**Document:** `documents/email/vehicle-approval.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  mail-send payload (Patchbay integration, v1) for the "Send state fee invoice email"
  service task in vehicle-registration.bpmn. Sent when the applicant
  provided an email address; attaches the State fee invoice PDF
  produced by the preceding "Generate state fee invoice" task.

  Variables in scope: firstName, lastName, age, objectId (vehicle code),
  price (vehicle value), applicantEmail, approvalPdfBytes
  (raw PDF bytes — the state fee invoice), approvalPdfFilename.

  The BPMN gateway guarantees applicantEmail is non-null and contains '@'
  before this template runs, but we still null-default to keep the
  template robust if invoked from a different path. ?json_string escapes
  embedded quotes/backslashes/newlines so the payload is always valid
  JSON. We base64-encode the PDF bytes here (rather than carrying a
  base64 String process variable) because String variables in CIB seven
  cap at 4000 chars in ACT_HI_VARINST.TEXT_.

  The Vehicle Registration Certificate is generated immediately after
  this email and stored as a process Attachment — visible in the
  Documents card. A future PR (payment step) will email it once the
  state fee is paid.

  The pay link carries a payment capability token minted by
  links.payment(execution) (docs/security.md rules 3 and 4), not the
  process instance id.

  The body is the pack document documents/email/vehicle-approval.ftl.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "${(applicantEmail!"")?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "Your vehicle registration is approved — state fee invoice attached",
  "text": "${documents.text("vehicle-approval", execution)?json_string}",
  "attachments": [
    {
      "filename": "${(approvalPdfFilename!"state-fee-invoice.pdf")?json_string}",
      "contentType": "application/pdf",
      "base64": "${pdf.encode(approvalPdfBytes)}"
    }
  ]
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
  "objectId": "VIN-1234567",
  "price": 38000,
  "approvalPdfBytes": {
    "$bytes": "fake-approval-pdf"
  }
}
```

| Path | Expected |
|---|---|
| `/from/email` | `"process@cib7-poc.local"` |
| `/to/0/email` | `"ants@example.com"` |
| `/to/0/name` | `"Ants Avaldaja"` |
| `/attachments/0/contentType` | `"application/pdf"` |
| `/attachments/0/base64` | `"ZmFrZS1hcHByb3ZhbC1wZGY="` |
| `/text` | `matches "/pay/[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+"` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- The pay link carries a payment capability token from `links.payment(execution)`, never the process instance id (docs/security.md rules 3 and 4).
- The fee counts as paid only on the payment provider's signed callback, which correlates `Task_WaitForPayment`; this email only invites the payment.
