<#--
  /api/documents/index-case payload for Task_TransportPermitIndexSubmitted.
  Case summary card for the backend's case-card store (search_cases
  MCP tool). Same processInstanceId at every milestone -> the index keeps
  one card per case. All vars defensively defaulted.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-learning-permit",
  "status": "submitted",
  "summary": "Driving learner permit application submitted by ${(applicantName!"")?json_string}, civil ID ${(civilId!"")?json_string}, license category ${(licenseCategory!"")?json_string}. Status: submitted — awaiting driver clearance checks (eye test, existing licenses, restrictions) and eligibility decision."
}
