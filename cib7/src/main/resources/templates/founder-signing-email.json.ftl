<#--
  Mailpit /api/v1/send payload for the "Send co-founder signing email"
  service task inside SubProcess_FounderSignatures in
  business-registration.bpmn.

  Scope: this template runs inside one multi-instance subprocess iteration.
  The per-iteration element variable `founder` is a SpinJsonNode with prop()
  accessors for name / email / token. Process-scope variables
  (applicantFirstName, applicantLastName, companyName, frontendBaseUrl) are
  also in scope.

  ?json_string escapes embedded quotes / backslashes / newlines so the
  emitted payload is always valid JSON regardless of what the applicant
  typed into the co-founder editor.
-->
<#assign founderName = founder.prop("name").stringValue()>
<#assign founderEmail = founder.prop("email").stringValue()>
<#assign founderToken = founder.prop("token").stringValue()>
<#assign applicantName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign signUrl = frontendBaseUrl + "/sign-founder/" + founderToken>
<#assign body>Tere ${founderName},

${applicantName} has named you as a co-founder of ${companyName!""} and
needs your signature on the Articles of Association before the OÜ can be
entered in the Estonian Business Register (äriregister).

Open this link to review the founding details and approve or reject:
${signUrl}

If you reject, the case is sent back to ${applicantName} with the reason
you provide. Once every co-founder has signed, any founder can click
"Submit to register" on the signing page to forward the case to the
Business Register.

Tervitustega,
Äriregister POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To": [ { "Email": "${founderEmail?json_string}", "Name": "${founderName?json_string}" } ],
  "Subject": "${("Please sign: " + (companyName!"") + " (founder: " + applicantName + ")")?json_string}",
  "Text": "${body?json_string}"
}
