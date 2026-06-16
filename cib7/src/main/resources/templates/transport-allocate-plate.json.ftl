<#--
  /api/public/transport/plates/allocate payload for Task_TransportAllocatePlate in
  transport-vehicle-registration.bpmn. The backend persists the registration
  record and returns the allocated (or validated reserved) plate number.
  Variables in scope: execution, vin, applicantName, vehicleCategory,
  plateOption, reservedPlateNumber (empty string for random plates).
-->
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "vin": "${(vin!"")?json_string}",
  "ownerName": "${(applicantName!"")?json_string}",
  "vehicleCategory": "${(vehicleCategory!"")?json_string}",
  "plateOption": "${(plateOption!"random")?json_string}",
  "reservedPlateNumber": "${(reservedPlateNumber!"")?json_string}"
}
