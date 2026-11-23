# Service task: `store-bcard-pdf`

**Task id:** `store-bcard-pdf`
**BPMN task id:** `Task_StoreBcardPdf`
**Display name:** `Store B-card extract`
**Connector:** `http-connector`
**Async-before:** `true`

Stores the B-card extract as a case document (category `generated-certificate`).

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/internal/documents/server-upload` |
| Headers | `Content-Type: application/json` |

Downstream: backend documents module (the bus adds `X-Internal-Token`).

## Payload (request body)

```
payload-template: server-upload-bcard.json.ftl
```

```ftl
<#--
  /api/internal/documents/server-upload payload for Task_StoreBcardPdf in
  business-registration.bpmn.

  Sends the just-generated B-card extract PDF bytes (held in the
  bcardPdfBytes byte[] process variable) as a base64 string inside the
  JSON envelope. Backend decodes once, PUTs to RustFS under
  process/{piId}/..., creates a Camunda Attachment with category=
  generated-certificate.

  Category is generated-certificate (not generated-bcard): the B-card extract
  is the business registration's issued certificate, so the mobile wallet and
  the approval-status signal treat it the same as any other certificate. The
  "bcard-extract" filename keeps the document's specific identity.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(bcardPdfFilename!"bcard-extract.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(bcardPdfBytes)}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "bcardPdfBytes": {
    "$bytes": "fake-bcard-pdf"
  }
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `bcardPdfAttachmentId` | `String` | `${S(response).prop('attachmentId').stringValue()}` |

## Notes

- Category `generated-certificate`, not a B-card category: the extract is this service's issued certificate, so the mobile wallet and the approval-status signal treat it like any other. The filename keeps its identity.
