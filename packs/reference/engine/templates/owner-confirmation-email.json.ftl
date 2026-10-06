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

  The body is the pack document documents/email/vehicle-owner-signing.ftl.
-->
<#assign ownerName = owner.prop("name").stringValue()>
<#assign ownerEmail = owner.prop("email").stringValue()>
<#assign applicantName = (firstName!"") + " " + (lastName!"")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${ownerEmail?json_string}", "Name": "${ownerName?json_string}" } ],
  "Subject": "Please sign: vehicle registration with ${applicantName?json_string}",
  "Text": "${documents.text("vehicle-owner-signing", execution)?json_string}"
}
