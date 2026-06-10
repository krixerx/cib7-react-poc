<#--
  /api/documents/server-upload payload for Task_StoreBcardPdf in
  business-registration.bpmn.

  Sends the just-generated B-card extract PDF bytes (held in the
  bcardPdfBytes byte[] process variable) as a base64 string inside the
  JSON envelope. Backend decodes once, PUTs to RustFS under
  process/{piId}/..., creates a Camunda Attachment with category=
  generated-bcard.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(bcardPdfFilename!"bcard-extract.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-bcard",
  "base64": "${pdf.encode(bcardPdfBytes)}"
}
