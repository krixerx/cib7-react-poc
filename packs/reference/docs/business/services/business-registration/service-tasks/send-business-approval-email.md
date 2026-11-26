# Service task: `send-business-approval-email`

**Task id:** `send-business-approval-email`
**BPMN task id:** `Task_SendApprovalEmail`
**Display name:** `Send approval email`
**Connector:** `http-connector`
**Async-before:** `true`

Tells the applicant the OÜ is approved, attaches the state fee invoice and gives the pay link.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: business-approval-email.json.ftl
```

**Document:** `documents/email/business-approval.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  mail-send payload (Patchbay integration, v1) for the businessRegistration approval email.
  Variables in scope: companyName, shareCapital, applicantFirstName,
  applicantLastName, applicantEmail, boardMembers (Spin Json list),
  autoDecision, decision, feeInvoicePdfBytes (raw PDF bytes, written by
  the preceding Task_GenerateFeeInvoicePdf) and feeInvoicePdfFilename,
  plus the execution properties.

  All string fields escaped with ?json_string. boardMembers iterated via
  Spin's elements() iterator and stringified for the human-readable body.

  Synthesises a Business Register code from the process instance id so
  the email reads like a real Äriregister confirmation. Demo only — the
  real code is allocated by the registry.

  We base64-encode the PDF bytes here (rather than carrying a base64
  String process variable) because String variables in CIB seven cap at
  4000 chars in ACT_HI_VARINST.TEXT_. Same byte[]→base64 trip as
  the vehicle service's approval-email.json.ftl.

  Recipient is the applicant's own email — Gateway_SendApprovalEmail
  upstream guarantees it is non-null and contains '@' before this
  template runs.

  The pay link carries a payment capability token minted by
  links.payment(execution) (docs/security.md rules 3 and 4), not the
  process instance id.

  The body is the pack document documents/email/business-approval.ftl.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
{
  "from":    { "email": "process@cib7-poc.local", "name": "Äriregister POC" },
  "to":      [ { "email": "${(applicantEmail!"")?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "${("Estonian OÜ registered: " + (companyName!""))?json_string}",
  "text":    "${documents.text("business-approval", execution)?json_string}",
  "attachments": [
    {
      "filename": "${(feeInvoicePdfFilename!"state-fee-invoice.pdf")?json_string}",
      "contentType": "application/pdf",
      "base64": "${pdf.encode(feeInvoicePdfBytes)}"
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
  "applicantFirstName": "Frida",
  "applicantLastName": "Asutaja",
  "applicantResidency": "e-resident",
  "companyName": "Näidis OÜ",
  "shareCapital": 2500,
  "autoDecision": "approve",
  "decision": "approve",
  "boardMembers": [
    {
      "firstName": "Mari",
      "lastName": "Maasikas",
      "personalCode": "48001010000"
    }
  ],
  "founder": {
    "name": "Karl Kaasasutaja",
    "email": "karl@example.com",
    "partyId": "p1"
  },
  "feeInvoicePdfBytes": {
    "$bytes": "fake-invoice-pdf"
  }
}
```

| Path | Expected |
|---|---|
| `/from/email` | `"process@cib7-poc.local"` |
| `/to/0/email` | `"ants@example.com"` |
| `/to/0/name` | `"Frida Asutaja"` |
| `/attachments/0/contentType` | `"application/pdf"` |
| `/attachments/0/base64` | `"ZmFrZS1pbnZvaWNlLXBkZg=="` |
| `/text` | `matches "/pay/[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+"` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- The pay link carries a payment capability token from `links.payment(execution)` (docs/security.md rules 3 and 4).
- The applicant email comes from the verified account; the form cannot change it.
