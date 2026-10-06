<#--
  Mailpit /api/v1/send payload for the "Send owner tracking email"
  service task in vehicle-registration.bpmn.

  Sent once at the start of the co-owner signing phase. The owner is
  pre-confirmed by the form submission, so the link mainly
  exists so the owner sees the same page as every other co-owner and
  can click "Send to Transport Authority" once the round of signatures
  completes.

  Scope: process variables firstName, lastName, applicantEmail,
  additionalOwners; reserved beans frontendBaseUrl and links. The token is
  minted by links.owner(execution, "applicant") and never stored
  (docs/security.md rule 3).

  The body is the pack document documents/email/vehicle-applicant-tracking.ftl.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your vehicle registration is awaiting co-owner signatures",
  "Text": "${documents.text("vehicle-applicant-tracking", execution)?json_string}"
}
