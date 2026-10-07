# Service task: `store-certificate-pdf`

**Task id:** `store-certificate-pdf`
**BPMN task id:** `Task_StoreCertificatePdf`
**Display name:** `Store registration certificate`
**Connector:** `http-connector`
**Async-before:** `true`

Stores the certificate as a case document (category `generated-certificate`), where the applicant downloads it.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/internal/documents/server-upload` |
| Headers | `Content-Type: application/json` |

Downstream: backend documents module (the bus adds `X-Internal-Token`).

## Payload (request body)

```
payload-template: server-upload-certificate.json.ftl
```

```ftl
<#--
  /api/internal/documents/server-upload payload for Task_StoreCertificatePdf.
  Mirror of server-upload-approval.json.ftl with the certificate's
  variable names and a different category.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(certificatePdfFilename!"certificate.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(certificatePdfBytes)}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "certificatePdfBytes": {
    "$bytes": "fake-certificate-pdf"
  }
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `certificatePdfAttachmentId` | `String` | `${S(response).prop('attachmentId').stringValue()}` |

## Notes

- The category is the one the mobile wallet and the approval-status signal look for.
