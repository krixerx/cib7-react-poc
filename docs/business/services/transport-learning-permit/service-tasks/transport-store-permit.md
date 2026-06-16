# Service task: `transport-store-permit`

**BPMN task:** `Task_TransportStorePermit`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Persists the electronic learning license to document storage (RustFS via
server-upload) so it shows in the SPA's Documents card.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${apiBaseUrl}/api/documents/server-upload` | `Content-Type: application/json`, `X-Internal-Token: ${internalTaskToken}` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-store-permit.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "filename": "${permitPdfFilename!\"transport-learning-permit.pdf\"}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(permitPdfBytes)}"
}
```

(`generated-certificate` is the existing allowed category — reused for
all generated official documents.)

## Response → output parameters

| Output variable | Type | Expression |
|---|---|---|
| `permitAttachmentId` | String | `${S(response).prop('attachmentId').stringValue()}` |
