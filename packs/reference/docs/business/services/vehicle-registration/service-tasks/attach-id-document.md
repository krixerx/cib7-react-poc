# Service task: `attach-id-document`

**Task id:** `attach-id-document`
**BPMN task id:** `Task_AttachIdDocument`
**Display name:** `Attach owner ID document`
**Connector:** `http-connector`
**Async-before:** `true`

Moves the applicant's freshly uploaded ID document from the pending upload area into the case and records it as a case document (category `applicant-id-document`).

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/internal/documents/move-pending` |
| Headers | `Content-Type: application/json` |

Downstream: backend documents module (the bus adds `X-Internal-Token`).

## Payload (request body)

```
payload-template: move-pending.json.ftl
```

```ftl
<#--
  /api/internal/documents/move-pending payload for Task_AttachIdDocument.
  Copies the just-uploaded object out of the pending/ prefix and into
  process/{piId}/..., creates a Camunda Attachment with type=
  applicant-id-document, deletes the pending object.

  pendingIdDocument is a SpinJsonNode written by the applicant form
  carrying the pendingKey, filename, and contentType returned by the
  /api/documents/upload-url call. Gateway_HasPendingUpload upstream
  guarantees the variable is non-null before this template runs.

  processInstanceId comes from execution; ?json_string escapes
  embedded quotes/backslashes/newlines (paths from the upload-url
  endpoint are tame UUIDs but the filename comes from the user).
-->
{
  "pendingKey": "${pendingIdDocument.prop("pendingKey").stringValue()?json_string}",
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${pendingIdDocument.prop("filename").stringValue()?json_string}",
  "contentType": "${pendingIdDocument.prop("contentType").stringValue()?json_string}",
  "category": "applicant-id-document"
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `idDocumentAttachmentId` | `String` | `${S(response).prop('attachmentId').stringValue()}` |

## Notes

- Runs only when `Gateway_HasPendingUpload` sees a non-null `pendingIdDocument`; a resubmission that keeps the earlier document writes `null` and skips this task.
- The pending key is a variable the applicant wrote, so the backend moves it only when it lies under the case initiator's own `pending/<user>/` prefix (`InternalDocumentsController`, docs/security.md rule 5).
- The filename is user input, so every value is `?json_string`-escaped.
