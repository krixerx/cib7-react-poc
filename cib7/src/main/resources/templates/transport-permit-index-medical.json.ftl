<#--
  /api/documents/index-case payload for Task_TransportPermitIndexMedical.
  Card refresh: routed to the medical assessment branch — indexed before the
  human task so the card is fresh during the wait.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-learning-permit",
  "status": "awaiting-medical",
  "summary": "Driving learner permit application by ${(applicantName!"")?json_string}, civil ID ${(civilId!"")?json_string}, license category ${(licenseCategory!"")?json_string}. Status: on hold — the applicant must attend a medical fitness assessment at the hospital before the application can proceed. The case is waiting for the medical assessment result."
}
