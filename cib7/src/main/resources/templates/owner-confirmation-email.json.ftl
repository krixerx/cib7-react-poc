<#--
  Mailpit /api/v1/send payload for the "Send owner confirmation email"
  service task inside SubProcess_OwnerConfirmations in
  person-registration.bpmn.

  Scope: this template runs inside one multi-instance subprocess iteration.
  The per-iteration element variable `owner` is a SpinJsonNode with prop()
  accessors for name / email / token. Process-scope variables (firstName,
  lastName, frontendBaseUrl) are also in scope.

  ?json_string escapes embedded quotes / backslashes / newlines so the
  emitted payload is always valid JSON regardless of what the applicant
  typed into the owner editor.
-->
<#assign ownerName = owner.prop("name").stringValue()>
<#assign ownerEmail = owner.prop("email").stringValue()>
<#assign ownerToken = owner.prop("token").stringValue()>
<#assign applicantName = (firstName!"") + " " + (lastName!"")>
<#assign confirmUrl = frontendBaseUrl + "/confirm-owner/" + ownerToken>
<#assign body>Hello ${ownerName},

${applicantName} has named you as a co-owner of a new company registration
and needs your signature before it can proceed.

Open this link to review the application and approve or reject:
${confirmUrl}

If you reject the application, the case is sent back to ${applicantName}
with the reason you provide. Once every co-owner has signed, any owner
can click "Send to process" on the confirmation page to forward the case
to the back office.

Thanks,
CIB7 POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To": [ { "Email": "${ownerEmail?json_string}", "Name": "${ownerName?json_string}" } ],
  "Subject": "Please sign: ${applicantName?json_string}'s registration",
  "Text": "${body?json_string}"
}
