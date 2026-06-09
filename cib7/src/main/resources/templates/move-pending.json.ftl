<#--
  /api/documents/move-pending payload for Task_AttachIdDocument.
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
