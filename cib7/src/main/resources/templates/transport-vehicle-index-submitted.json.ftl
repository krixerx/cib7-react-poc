<#--
  /api/documents/index-case payload for Task_TransportVehicleIndexSubmitted.
  Case summary card for the backend's case-card store (search_cases
  MCP tool). Same processInstanceId at every milestone -> the index keeps
  one card per case. The summary is prose for an LLM reader, not a UI.
  All vars defensively defaulted: MCP-started instances may omit
  form-internal variables.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-vehicle-registration",
  "status": "submitted",
  "summary": "Transport vehicle registration application submitted by ${(applicantName!"")?json_string} for vehicle VIN ${(vin!"")?json_string}, category ${(vehicleCategory!"")?json_string}, registration type ${(registrationType!"")?json_string}, plate option ${(plateOption!"")?json_string}. Status: submitted — awaiting automated clearance checks (inspection, insurance, restrictions) and officer review."
}
