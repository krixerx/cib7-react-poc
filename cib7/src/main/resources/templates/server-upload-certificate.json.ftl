<#--
  /api/documents/server-upload payload for Task_StoreCertificatePdf.
  Mirror of server-upload-approval.json.ftl with the certificate's
  variable names and a different category.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "filename": "${(certificatePdfFilename!"certificate.pdf")?json_string}",
  "contentType": "application/pdf",
  "category": "generated-certificate",
  "base64": "${pdf.encode(certificatePdfBytes)}"
}
