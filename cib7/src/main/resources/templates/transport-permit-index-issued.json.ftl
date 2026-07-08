<#--
  /api/documents/index-case payload for Task_TransportPermitIndexIssued.
  Terminal happy-path card: permit issued, electronic license stored.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-learning-permit",
  "status": "issued",
  "summary": "Driving learner permit for ${(applicantName!"")?json_string}, civil ID ${(civilId!"")?json_string}, license category ${(licenseCategory!"")?json_string}. Status: completed — learning permit ${(permitNumber!"")?json_string} issued, valid until ${(permitValidUntil!"")?json_string}, electronic license stored in the case documents. The case is closed successfully."
}
