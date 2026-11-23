<#--
  pdf-renderer /render payload for Task_GeneratePdf in vehicle-registration.bpmn: the vehicle state fee invoice.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/vehicle-fee-invoice.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("vehicle-fee-invoice", execution)?json_string}",
  "filename": "state-fee-invoice-${(objectId!"vehicle")?json_string}.pdf"
}
