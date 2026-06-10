<#--
  /api/documents/move-pending payload for Task_AttachAoaDocument in
  business-registration.bpmn. Mirror of move-pending.json.ftl — same
  shape, different source variable + category.

  Copies the just-uploaded Articles of Association object out of the
  pending/ prefix and into process/{piId}/..., creates a Camunda
  Attachment with type=founder-articles-of-association, deletes the
  pending object.

  pendingAoaDocument is a SpinJsonNode written by the founder form
  carrying the pendingKey, filename, and contentType returned by the
  /api/documents/upload-url call. Gateway_HasPendingAoa upstream
  guarantees the variable is non-null before this template runs.

  processInstanceId comes from execution; ?json_string escapes embedded
  quotes/backslashes/newlines (paths from the upload-url endpoint are
  tame UUIDs but the filename comes from the user).
-->
{
  "pendingKey": "${pendingAoaDocument.prop("pendingKey").stringValue()?json_string}",
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${pendingAoaDocument.prop("filename").stringValue()?json_string}",
  "contentType": "${pendingAoaDocument.prop("contentType").stringValue()?json_string}",
  "category": "founder-articles-of-association"
}
