<#--
  /api/documents/index-case payload for Task_TransportVehicleIndexRegistered.
  Terminal happy-path card: plate allocated, certificate stored.
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-vehicle-registration",
  "status": "registered",
  "summary": "Transport vehicle registration by ${(applicantName!"")?json_string} for vehicle VIN ${(vin!"")?json_string}, category ${(vehicleCategory!"")?json_string}. Status: completed — vehicle registered, plate number ${(plateNumber!"")?json_string} allocated, registration certificate issued and available in the case documents. The case is closed successfully."
}
