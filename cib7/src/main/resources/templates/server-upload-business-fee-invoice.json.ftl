<#--
  /api/documents/server-upload payload for Task_StoreFeeInvoicePdf in
  business-registration.bpmn.

  Sends the just-generated fee invoice PDF bytes (held in the
  feeInvoicePdfBytes byte[] process variable) as a base64 string inside
  the JSON envelope. Backend decodes once, PUTs to RustFS under
  process/{piId}/..., creates a Camunda Attachment with category=
  generated-business-fee-invoice.

  feeInvoicePdfBytes stays in scope after this task so the subsequent
  Task_SendApprovalEmail can re-encode it for the Mailpit attachment —
  same byte[]→base64 trip as personReg's approval PDF pipeline.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(feeInvoicePdfFilename!"state-fee-invoice.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-business-fee-invoice",
  "base64": "${pdf.encode(feeInvoicePdfBytes)}"
}
