# Service task: `store-business-fee-invoice-pdf`

**Task id:** `store-business-fee-invoice-pdf`
**BPMN task id:** `Task_StoreFeeInvoicePdf`
**Display name:** `Store fee invoice`
**Connector:** `http-connector`
**Async-before:** `true`

Stores the state fee invoice as a case document (category `generated-business-fee-invoice`).

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/internal/documents/server-upload` |
| Headers | `Content-Type: application/json` |

Downstream: backend documents module (the bus adds `X-Internal-Token`).

## Payload (request body)

```
payload-template: server-upload-business-fee-invoice.json.ftl
```

```ftl
<#--
  /api/internal/documents/server-upload payload for Task_StoreFeeInvoicePdf in
  business-registration.bpmn.

  Sends the just-generated fee invoice PDF bytes (held in the
  feeInvoicePdfBytes byte[] process variable) as a base64 string inside
  the JSON envelope. Backend decodes once, PUTs to RustFS under
  process/{piId}/..., creates a Camunda Attachment with category=
  generated-business-fee-invoice.

  feeInvoicePdfBytes stays in scope after this task so the subsequent
  Task_SendApprovalEmail can re-encode it for the Mailpit attachment —
  same byte[]→base64 trip as the vehicle service's state fee invoice.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(feeInvoicePdfFilename!"state-fee-invoice.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-business-fee-invoice",
  "base64": "${pdf.encode(feeInvoicePdfBytes)}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "feeInvoicePdfBytes": {
    "$bytes": "fake-invoice-pdf"
  }
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `feeInvoicePdfAttachmentId` | `String` | `${S(response).prop('attachmentId').stringValue()}` |

## Notes

- `feeInvoicePdfBytes` stays on the case: the approval email attaches the same PDF.
