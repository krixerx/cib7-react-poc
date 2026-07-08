<#--
  /api/documents/index-case payload for Task_TransportVehicleIndexSendback.
  Card refresh: officer returned the application for corrections.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-vehicle-registration",
  "status": "sent-back",
  "summary": "Transport vehicle registration application by ${(applicantName!"")?json_string} for vehicle VIN ${(vin!"")?json_string}. Status: sent back — returned to the applicant for corrections by the reviewing officer. Reason: ${(sendBackReason!"not specified")?json_string}. Waiting for the applicant to amend and resubmit the application."
}
