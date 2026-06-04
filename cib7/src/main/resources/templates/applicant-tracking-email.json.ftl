<#--
  Mailpit /api/v1/send payload for the "Send applicant tracking email"
  service task in person-registration.bpmn.

  Sent once at the start of the owner-confirmation phase. The applicant's
  own token is pre-confirmed by the form submission, so the link mainly
  exists so the applicant sees the same page as every other owner and can
  click "Send to process" once the round of signatures completes.

  Scope: process variables firstName, lastName, applicantEmail,
  applicantToken, additionalOwners, frontendBaseUrl.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign confirmUrl = frontendBaseUrl + "/confirm-owner/" + applicantToken>
<#assign extraCount = additionalOwners.elements()?size>
<#assign body>Hi ${firstName!""},

Your registration has been submitted and is now waiting for ${extraCount}
co-owner signature<#if extraCount != 1>s</#if> before it goes to the back
office.

Track the signatures and forward the case once everyone has signed:
${confirmUrl}

Your own signature is recorded automatically. Once every co-owner has
signed, any owner can click "Send to process" on the page above.

Thanks,
CIB7 POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your registration is awaiting co-owner signatures",
  "Text": "${body?json_string}"
}
