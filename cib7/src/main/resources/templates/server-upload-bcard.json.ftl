<#--
  /api/documents/server-upload payload for Task_StoreBcardPdf in
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
