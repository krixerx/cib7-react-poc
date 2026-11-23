<#--
  pdf-renderer /render payload for Task_GenerateCertificatePdf in vehicle-registration.bpmn: the Vehicle Registration Certificate.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/vehicle-certificate.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("vehicle-certificate", execution)?json_string}",
  "filename": "vehicle-registration-certificate-${execution.processInstanceId?json_string}.pdf"
}
