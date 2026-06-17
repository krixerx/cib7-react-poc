# Service task: `transport-store-certificate`

**BPMN task:** `Task_TransportStoreCertificate`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Persists the certificate to document storage (RustFS via the backend's
server-upload), so it appears in the SPA's Documents card and survives
independently of the email.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/server-upload` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-store-certificate.json.ftl` —
mirror of `server-upload-certificate.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "filename": "${certificatePdfFilename!\"transport-registration-certificate.pdf\"}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(certificatePdfBytes)}"
}
```

(`generated-certificate` is already in the backend's
`ALLOWED_CATEGORIES` — no backend change needed.)

## Response → output parameters

| Output variable | Type | Expression |
|---|---|---|
| `certificateAttachmentId` | String | `${S(response).prop('attachmentId').stringValue()}` |

## Why these notes matter

- Goes through the **internal** chain (`X-Internal-Token`), not the public
  one — server-upload writes documents on behalf of the engine. The engine no
  longer sets that header; the call crosses the integration bus (`esb`), which
  injects `X-Internal-Token` on the `/api/documents` route. The spec table above
  therefore lists no token header — `/service-builder` must not re-add it.
- The bytes stay in `certificatePdfBytes` for the subsequent email task's
  attachment; this task only re-encodes for transport.
