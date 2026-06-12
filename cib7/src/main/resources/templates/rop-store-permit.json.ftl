<#--
  /api/documents/server-upload payload for Task_RopStorePermit in
  rop-learning-permit.bpmn. category generated-certificate is the existing
  allowed category for generated official documents.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(permitPdfFilename!"rop-learning-permit.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(permitPdfBytes)}"
}
