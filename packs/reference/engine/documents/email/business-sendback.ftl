<#--
  Email body: business-sendback. Plain text; the payload template templates/business-sendback-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign toEmail = ((applicantEmail!"")?contains("@"))?then(applicantEmail, (initiator!"applicant") + "@cib7-poc.local")>
Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your Estonian OÜ registration for "${(companyName!"")}" was sent back for
corrections by the Business Register reviewer.

Reason: ${(sendBackReason!"")}

Please open the portal at ${(frontendBaseUrl!"http://localhost:3000")} and
update your founding details. The reviewer's reason will be shown at the
top of the form.

Tervitustega,
Äriregister POC

<#include "/email/_footer.ftl">
