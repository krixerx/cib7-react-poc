<#--
  /api/documents/index-case payload for Task_TransportVehicleIndexRejected.
  Card refresh: rejected. Covers both reject paths — rejectionReason only
  exists on the officer path; the default supplies the system (DMN) phrase.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-vehicle-registration",
  "status": "rejected",
  "summary": "Transport vehicle registration application by ${(applicantName!"")?json_string} for vehicle VIN ${(vin!"")?json_string}. Status: rejected. Reason: ${(rejectionReason!"automated eligibility checks failed (vehicle inspection, insurance, or outstanding restrictions)")?json_string}. The case is closed."
}
