<#--
  mail-send payload (Patchbay integration, v1) for Task_SendBackEmail in
  vehicle-registration.bpmn: tells the applicant the case came back, after a
  reviewer's send-back or a co-owner's rejection (both write sendBackReason).
  The body is the pack document documents/email/vehicle-sendback.ftl.

  Same recipient rule as the business send-back email: the applicant's email
  from the account, else the initiator's demo address.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "${toEmail?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "Your vehicle registration needs corrections",
  "text": "${documents.text("vehicle-sendback", execution)?json_string}"
}
