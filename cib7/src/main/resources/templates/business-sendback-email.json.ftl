<#--
  Mailpit /api/v1/send payload for the businessRegistration send-back email.
  Variables in scope: companyName, applicantFirstName, applicantLastName,
  sendBackReason, frontendBaseUrl (from FrontendConfiguration).
-->
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
  "To":      [ { "Email": "applicant@cib7-poc.local" } ],
  "Subject": "${("OÜ registration sent back for corrections: " + (companyName!""))?json_string}",
  "Text":    "${body?json_string}"
}
