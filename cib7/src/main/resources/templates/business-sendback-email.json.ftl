<#--
  Mailpit /api/v1/send payload for the businessRegistration send-back email.
  Variables in scope: companyName, applicantFirstName, applicantLastName,
  applicantEmail (optional), initiator, sendBackReason, frontendBaseUrl
  (from FrontendConfiguration).

  Recipient: the applicant's own email when they gave one, otherwise the
  initiator-derived demo address — same rule the vehicle process uses. A
  fixed address here would silently swallow every other user's send-backs.
-->
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
<#assign body>Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your Estonian OÜ registration for "${(companyName!"")}" was sent back for
corrections by the Business Register reviewer.

Reason: ${(sendBackReason!"")}

Please open the portal at ${(frontendBaseUrl!"http://localhost:3000")} and
update your founding details. The reviewer's reason will be shown at the
top of the form.

Tervitustega,
Äriregister POC
</#assign>
{
  "From":    { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To":      [ { "Email": "${toEmail?json_string}" } ],
  "Subject": "${("OÜ registration sent back for corrections: " + (companyName!""))?json_string}",
  "Text":    "${body?json_string}"
}
