<#--
  Mailpit /api/v1/send payload for Task_RopVehicleRejectionEmail in
  rop-vehicle-registration.bpmn. Serves BOTH rejection paths:
    - system rejection: the DMN's eligibilityDecision text IS the reason
    - officer rejection: rejectionReason (explicit) wins
  ?has_content distinguishes the officer's empty-string non-reject value
  from a real reason. Variables in scope: applicantName, applicantEmail,
  vin, vehicleCategory, rejectionReason (may be unset), eligibilityDecision.
-->
<#assign reason = ((rejectionReason!"")?has_content)?then(rejectionReason!"", eligibilityDecision!"Conditions not met")>
<#assign body>Dear ${applicantName!""},

Your vehicle registration application (chassis number ${vin!""},
category ${vehicleCategory!""}) has been rejected.

Reason:
${reason}

Once the condition above is resolved, you are welcome to submit a new
application through the portal.

Royal Oman Police — General Traffic Department
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Royal Oman Police — General Traffic Department" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Your vehicle registration application was rejected",
  "Text": "${body?json_string}"
}
