<#--
  Mailpit /api/v1/send payload for the "Send applicant tracking email"
  service task in business-registration.bpmn.

  Sent once at the start of the co-founder signing phase. The applicant is
  pre-confirmed by the form submission, so the link mainly
  exists so the applicant sees the same page as every other co-founder
  and can click "Submit to register" once the round of signatures
  completes.

  Scope: process variables applicantFirstName, applicantLastName,
  applicantEmail, additionalFounders, companyName; reserved beans
  frontendBaseUrl and links. The token is minted by
  links.consent(execution, "founder", "applicant") and never stored (docs/security.md
  rule 3).

  The body is the pack document documents/email/business-founder-tracking.ftl.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "${("Your OÜ registration is awaiting co-founder signatures: " + (companyName!""))?json_string}",
  "Text": "${documents.text("business-founder-tracking", execution)?json_string}"
}
