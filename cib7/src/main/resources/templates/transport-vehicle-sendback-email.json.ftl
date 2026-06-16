<#--
  Mailpit /api/v1/send payload for Task_TransportVehicleSendbackEmail in
  transport-vehicle-registration.bpmn. The traffic officer returned the
  application for corrections; sendBackReason carries the notes, and the
  same variable renders as a banner when the applicant reopens the form.
  Variables in scope: applicantName, applicantEmail, vin, sendBackReason.
-->
<#assign body>Dear ${applicantName!""},

Your vehicle registration application (chassis number ${vin!""}) needs
corrections before it can be processed.

Notes from the traffic officer:
${sendBackReason!""}

Please open your case in the portal, update the application, and resubmit.

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Your vehicle registration application needs corrections",
  "Text": "${body?json_string}"
}
