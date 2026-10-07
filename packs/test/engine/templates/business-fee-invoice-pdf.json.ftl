<#--
  pdf-renderer /render payload for the state fee invoice task in business-registration.bpmn: the OÜ state fee invoice.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/business-fee-invoice.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("business-fee-invoice", execution)?json_string}",
  "filename": "state-fee-invoice-${(companyName!"OU")?json_string}.pdf"
}
