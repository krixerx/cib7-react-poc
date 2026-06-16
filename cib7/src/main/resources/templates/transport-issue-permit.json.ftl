<#--
  /api/public/transport/learning-permits/issue payload for Task_TransportIssuePermit
  in transport-learning-permit.bpmn. The backend persists the permit and returns
  permitNumber + validUntil (one year). Variables in scope: execution,
  civilId, applicantName, licenseCategory.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "civilId": "${(civilId!"")?json_string}",
  "applicantName": "${(applicantName!"")?json_string}",
  "licenseCategory": "${(licenseCategory!"")?json_string}"
}
