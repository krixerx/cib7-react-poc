<#--
  Email body: business-founder-tracking. Plain text; the payload template templates/founder-tracking-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign signUrl = frontendBaseUrl + "/sign-founder/" + links.consent(execution, "founder", "applicant")>
<#assign extraCount = additionalFounders.elements()?size>
Tere ${applicantFirstName!""},

Your registration for ${companyName!""} has been submitted and is now
waiting for ${extraCount} co-founder signature<#if extraCount != 1>s</#if>
before it can be sent to the Business Register.

Track the signatures and forward the case once everyone has signed:
${signUrl}

Your own signature is recorded automatically. Once every co-founder has
signed, any founder can click "Submit to register" on the page above.

Tervitustega,
Äriregister POC

<#include "/email/_footer.ftl">
