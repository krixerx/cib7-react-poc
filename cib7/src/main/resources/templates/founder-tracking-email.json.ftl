<#--
  Mailpit /api/v1/send payload for the "Send applicant tracking email"
  service task in business-registration.bpmn.

  Sent once at the start of the co-founder signing phase. The applicant's
  own token is pre-confirmed by the form submission, so the link mainly
  exists so the applicant sees the same page as every other co-founder
  and can click "Submit to register" once the round of signatures
  completes.

  Scope: process variables applicantFirstName, applicantLastName,
  applicantEmail, applicantToken, additionalFounders, companyName,
  frontendBaseUrl.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign signUrl = frontendBaseUrl + "/sign-founder/" + applicantToken>
<#assign extraCount = additionalFounders.elements()?size>
<#assign body>Tere ${applicantFirstName!""},

Your registration for ${companyName!""} has been submitted and is now
waiting for ${extraCount} co-founder signature<#if extraCount != 1>s</#if>
before it can be sent to the Business Register.

Track the signatures and forward the case once everyone has signed:
${signUrl}

Your own signature is recorded automatically. Once every co-founder has
signed, any founder can click "Submit to register" on the page above.

Tervitustega,
Äriregister POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "${("Your OÜ registration is awaiting co-founder signatures: " + (companyName!""))?json_string}",
  "Text": "${body?json_string}"
}
