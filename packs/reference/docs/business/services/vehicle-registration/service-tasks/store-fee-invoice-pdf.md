# Service task: `store-fee-invoice-pdf`

**Task id:** `store-fee-invoice-pdf`
**BPMN task id:** `Task_StoreApprovalPdf`
**Display name:** `Store fee invoice`
**Connector:** `http-connector`
**Async-before:** `true`

Stores the generated state fee invoice as a case document (category `generated-approval-pdf`).

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/internal/documents/server-upload` |
| Headers | `Content-Type: application/json` |

Downstream: backend documents module (the bus adds `X-Internal-Token`).

## Payload (request body)

```
payload-template: server-upload-approval.json.ftl
```

```ftl
<#--
  /api/internal/documents/server-upload payload for Task_StoreApprovalPdf.
  Sends the just-generated approval PDF bytes (held in the
  approvalPdfBytes byte[] process variable) as a base64 string inside
  the JSON envelope. Backend decodes once, PUTs to RustFS under
  process/{piId}/..., creates a Camunda Attachment with type=
  generated-approval-pdf.

  approvalPdfBytes stays in scope after this task so the subsequent
  Task_SendApprovalEmail can still re-encode it for the Mailpit
  attachment — see approval-email.json.ftl.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(approvalPdfFilename!"approval.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-approval-pdf",
  "base64": "${pdf.encode(approvalPdfBytes)}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "approvalPdfBytes": {
    "$bytes": "fake-approval-pdf"
  }
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `approvalPdfAttachmentId` | `String` | `${S(response).prop('attachmentId').stringValue()}` |

## Notes

- `approvalPdfBytes` stays on the case after this task: the next task attaches the same PDF to the approval email.
