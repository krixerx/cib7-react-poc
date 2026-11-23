<#--
  Email body: business-founder-signing. Plain text; the payload template templates/founder-signing-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign founderName = founder.prop("name").stringValue()>
<#assign founderEmail = founder.prop("email").stringValue()>
<#assign founderToken = links.consent(execution, "founder", founder.prop("partyId").stringValue())>
<#assign applicantName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign signUrl = frontendBaseUrl + "/consent/founder/" + founderToken>
Tere ${founderName},

${applicantName} has named you as a co-founder of ${companyName!""} and
needs your signature on the Articles of Association before the OÜ can be
entered in the Estonian Business Register (äriregister).

Open this link to review the founding details and approve or reject:
${signUrl}

If you reject, the case is sent back to ${applicantName} with the reason
you provide. Once every co-founder has signed, any founder can click
"Submit to register" on the signing page to forward the case to the
Business Register.

Tervitustega,
Äriregister POC

<#include "/email/_footer.ftl">
