<#--
  pdf-renderer /render payload for the B-card extract task in business-registration.bpmn: the B-card extract.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/bcard-extract.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("bcard-extract", execution)?json_string}",
  "filename": "bcard-extract-${(companyName!"OU")?json_string}.pdf"
}
