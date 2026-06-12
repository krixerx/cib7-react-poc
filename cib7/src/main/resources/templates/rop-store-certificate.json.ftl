<#--
  /api/documents/server-upload payload for Task_RopStoreCertificate in
  rop-vehicle-registration.bpmn. Mirror of server-upload-certificate.json.ftl
  with the ROP certificate's variable names; category generated-certificate
  is the existing allowed category for generated official documents.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(certificatePdfFilename!"rop-registration-certificate.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(certificatePdfBytes)}"
}
