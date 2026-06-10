<#--
  Mailpit /api/v1/send payload for the businessRegistration approval email.
  Variables in scope: companyName, shareCapital, applicantFirstName,
  applicantLastName, boardMembers (Spin Json list), autoDecision, decision,
  plus the execution properties.

  All string fields escaped with ?json_string. boardMembers iterated via
  Spin's elements() iterator and stringified for the human-readable body.

  Synthesises a Business Register code from the process instance id so
  the email reads like a real Ariregister confirmation. Demo only — the
  real code is allocated by the registry.
-->
<#assign caseRef = execution.processInstanceId>
<#assign regCode = "1" + caseRef?replace("-", "")?substring(0, 7)>
<#assign members>
<#list boardMembers.elements() as m>
- ${(m.prop("firstName").stringValue())!""} ${(m.prop("lastName").stringValue())!""} (isikukood ${(m.prop("personalCode").stringValue())!""})
</#list>
</#assign>
<#assign body>Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your Estonian OÜ has been entered in the Business Register (äriregister)
and is now ready to operate.

Business Register code:  ${regCode}
Company:                 ${(companyName!"")}
Share capital:           ${(shareCapital!0.0)?string("0.00")} EUR
Board members:
${members}
Decision made by:        ${((autoDecision!"")=="approve")?then("automated decision table (DMN)", "manual review by the Business Register")}

The B-card extract will be available in the My processes page in the SPA.

Tervitustega,
Äriregister POC
</#assign>
{
  "From":    { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To":      [ { "Email": "applicant@cib7-poc.local" } ],
  "Subject": "${("Estonian OÜ registered: " + (companyName!""))?json_string}",
  "Text":    "${body?json_string}"
}
