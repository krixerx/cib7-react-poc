<#--
  /api/documents/server-upload payload for Task_StoreApprovalPdf.
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
