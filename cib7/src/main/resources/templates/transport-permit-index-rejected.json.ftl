<#--
  /api/documents/index-case payload for Task_TransportPermitIndexRejected.
  Card refresh: rejected. Covers both reject paths — rejectionReason may be
  absent on the DMN path; the default supplies a generic eligibility phrase.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-learning-permit",
  "status": "rejected",
  "summary": "Driving learner permit application by ${(applicantName!"")?json_string}, civil ID ${(civilId!"")?json_string}. Status: rejected. Reason: ${(rejectionReason!"eligibility requirements not met (age, eye test, existing license status, or medical assessment)")?json_string}. The case is closed."
}
