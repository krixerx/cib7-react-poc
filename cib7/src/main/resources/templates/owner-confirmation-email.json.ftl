<#--
  Mailpit /api/v1/send payload for the "Send co-owner signing email"
  service task inside SubProcess_OwnerConfirmations in
  vehicle-registration.bpmn.

  Scope: this template runs inside one multi-instance subprocess iteration.
  The per-iteration element variable `owner` is a SpinJsonNode with prop()
  accessors for partyId / name / email (ConsentPartiesListener wrote it).
  Process-scope variables (firstName, lastName) and the reserved beans
  frontendBaseUrl and links are also in scope.

  The confirmation token is minted here by links.owner(execution, partyId)
  and never stored: an HMAC over case, party, consentRound and expiry
  (docs/security.md rule 3).

  ?json_string escapes embedded quotes / backslashes / newlines so the
  emitted payload is always valid JSON regardless of what the applicant
  typed into the co-owner editor.
-->
<#assign ownerName = owner.prop("name").stringValue()>
<#assign ownerEmail = owner.prop("email").stringValue()>
<#assign ownerToken = links.owner(execution, owner.prop("partyId").stringValue())>
<#assign applicantName = (firstName!"") + " " + (lastName!"")>
<#assign confirmUrl = frontendBaseUrl + "/confirm-owner/" + ownerToken>
<#assign body>Hello ${ownerName},

${applicantName} has named you as a co-owner of a vehicle registration
with Transpordiamet and needs your signature before it can proceed.

Open this link to review the registration and approve or reject:
${confirmUrl}

If you reject, the case is sent back to ${applicantName} with the reason
you provide. Once every co-owner has signed, any owner can click "Send
to Transport Authority" on the confirmation page to forward the case for
review.

Thanks,
Transpordiamet POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${ownerEmail?json_string}", "Name": "${ownerName?json_string}" } ],
  "Subject": "Please sign: vehicle registration with ${applicantName?json_string}",
  "Text": "${body?json_string}"
}
