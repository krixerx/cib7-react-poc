<#--
  Email body: vehicle-applicant-tracking. Plain text; the payload template templates/applicant-tracking-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign confirmUrl = frontendBaseUrl + "/confirm-owner/" + links.consent(execution, "owner", "applicant")>
<#assign extraCount = additionalOwners.elements()?size>
Hi ${firstName!""},

Your vehicle registration has been submitted to Transpordiamet and is now
waiting for ${extraCount} co-owner signature<#if extraCount != 1>s</#if>
before it can be reviewed.

Track the signatures and forward the case once everyone has signed:
${confirmUrl}

Your own signature is recorded automatically. Once every co-owner has
signed, any owner can click "Send to Transport Authority" on the page
above.

Thanks,
Transpordiamet POC

<#include "/email/_footer.ftl">
