<#--
  Mailpit /api/v1/send payload for the businessRegistration send-back email.
  Variables in scope: companyName, applicantFirstName, applicantLastName,
  sendBackReason, frontendBaseUrl (from FrontendConfiguration).
-->
<#assign body>Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your business registration for "${(companyName!"")}" was sent back for
corrections by the reviewer.

Reason: ${(sendBackReason!"")}

Please open the portal at ${(frontendBaseUrl!"http://localhost:3000")} and
update your application. The reviewer's reason will be shown at the top of
the form.

Tere,
CIB7 POC
</#assign>
{
  "From":    { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To":      [ { "Email": "applicant@cib7-poc.local" } ],
  "Subject": "${("Business registration sent back: " + (companyName!""))?json_string}",
  "Text":    "${body?json_string}"
}
