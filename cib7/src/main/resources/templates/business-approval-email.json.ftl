<#--
  Mailpit /api/v1/send payload for the businessRegistration approval email.
  Variables in scope: companyName, shareCapital, applicantFirstName,
  applicantLastName, boardMembers (Spin Json list), autoDecision, decision.

  All string fields escaped with ?json_string. boardMembers iterated via
  Spin's elements() iterator and stringified for the human-readable body.
-->
<#assign members>
<#list boardMembers.elements() as m>
- ${(m.prop("firstName").stringValue())!""} ${(m.prop("lastName").stringValue())!""} (${(m.prop("personalCode").stringValue())!""})
</#list>
</#assign>
<#assign body>Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your business registration has been approved.

Company:        ${(companyName!"")}
Share capital:  ${(shareCapital!0.0)?string("0.00")} EUR
Board members:
${members}
This was a ${((autoDecision!"")=="approve")?then("an automated decision (DMN)", "manual review by a civil servant")}.

Tere,
CIB7 POC
</#assign>
{
  "From":    { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To":      [ { "Email": "applicant@cib7-poc.local" } ],
  "Subject": "${("Business registration approved: " + (companyName!""))?json_string}",
  "Text":    "${body?json_string}"
}
