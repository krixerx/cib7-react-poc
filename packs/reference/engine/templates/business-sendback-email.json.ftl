<#--
  mail-send payload (Patchbay integration, v1) for the businessRegistration send-back email.
  Variables in scope: companyName, applicantFirstName, applicantLastName,
  applicantEmail (optional), initiator, sendBackReason, frontendBaseUrl
  (from FrontendConfiguration).

  Recipient: the applicant's own email when they gave one, otherwise the
  initiator-derived demo address — same rule the vehicle process uses. A
  fixed address here would silently swallow every other user's send-backs.

  The body is the pack document documents/email/business-sendback.ftl.
-->
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
{
  "from":    { "email": "process@cib7-poc.local", "name": "Äriregister POC" },
  "to":      [ { "email": "${toEmail?json_string}" } ],
  "subject": "${("OÜ registration sent back for corrections: " + (companyName!""))?json_string}",
  "text":    "${documents.text("business-sendback", execution)?json_string}"
}
