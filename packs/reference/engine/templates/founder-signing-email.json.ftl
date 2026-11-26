<#--
  mail-send payload (Patchbay integration, v1) for the "Send co-founder signing email"
  service task inside SubProcess_FounderSignatures in
  business-registration.bpmn.

  Scope: this template runs inside one multi-instance subprocess iteration.
  The per-iteration element variable `founder` is a SpinJsonNode with prop()
  accessors for partyId / name / email (ConsentPartiesListener wrote it).
  Process-scope variables (applicantFirstName, applicantLastName,
  companyName) and the reserved beans frontendBaseUrl and links are also in
  scope.

  The signing token is minted here by links.consent(execution, "founder", partyId) and
  never stored: an HMAC over case, party, consentRound and expiry
  (docs/security.md rule 3).

  ?json_string escapes embedded quotes / backslashes / newlines so the
  emitted payload is always valid JSON regardless of what the applicant
  typed into the co-founder editor.

  The body is the pack document documents/email/business-founder-signing.ftl.
-->
<#assign founderName = founder.prop("name").stringValue()>
<#assign founderEmail = founder.prop("email").stringValue()>
<#assign applicantName = (applicantFirstName!"") + " " + (applicantLastName!"")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Äriregister POC" },
  "to": [ { "email": "${founderEmail?json_string}", "name": "${founderName?json_string}" } ],
  "subject": "${("Please sign: " + (companyName!"") + " (founder: " + applicantName + ")")?json_string}",
  "text": "${documents.text("business-founder-signing", execution)?json_string}"
}
