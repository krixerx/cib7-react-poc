<#--
  Mailpit /api/v1/send payload for Task_SendBackEmail in
  vehicle-registration.bpmn: tells the applicant the case came back, after a
  reviewer's send-back or a co-owner's rejection (both write sendBackReason).
  The body is the pack document documents/email/vehicle-sendback.ftl.

  Same recipient rule as the business send-back email: the applicant's email
  from the account, else the initiator's demo address.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${toEmail?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your vehicle registration needs corrections",
  "Text": "${documents.text("vehicle-sendback", execution)?json_string}"
}
