<#--
  Mailpit /api/v1/send payload for the businessRegistration send-back email.
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
  "From":    { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To":      [ { "Email": "${toEmail?json_string}" } ],
  "Subject": "${("OÜ registration sent back for corrections: " + (companyName!""))?json_string}",
  "Text":    "${documents.text("business-sendback", execution)?json_string}"
}
