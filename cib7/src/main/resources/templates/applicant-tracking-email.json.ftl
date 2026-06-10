<#--
  Mailpit /api/v1/send payload for the "Send owner tracking email"
  service task in vehicle-registration.bpmn.

  Sent once at the start of the co-owner signing phase. The owner's
  own token is pre-confirmed by the form submission, so the link mainly
  exists so the owner sees the same page as every other co-owner and
  can click "Send to Transport Authority" once the round of signatures
  completes.

  Scope: process variables firstName, lastName, applicantEmail,
  applicantToken, additionalOwners, frontendBaseUrl.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign confirmUrl = frontendBaseUrl + "/confirm-owner/" + applicantToken>
<#assign extraCount = additionalOwners.elements()?size>
<#assign body>Hi ${firstName!""},

Your vehicle registration has been submitted to Transpordiamet and is now
waiting for ${extraCount} co-owner signature<#if extraCount != 1>s</#if>
before it can be reviewed.

Track the signatures and forward the case once everyone has signed:
${confirmUrl}

Your own signature is recorded automatically. Once every co-owner has
signed, any owner can click "Send to Transport Authority" on the page
above.

Thanks,
Transpordiamet POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your vehicle registration is awaiting co-owner signatures",
  "Text": "${body?json_string}"
}
