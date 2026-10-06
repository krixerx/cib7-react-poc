<#--
  Email body: vehicle-owner-signing. Plain text; the payload template templates/owner-confirmation-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign ownerName = owner.prop("name").stringValue()>
<#assign ownerEmail = owner.prop("email").stringValue()>
<#assign ownerToken = links.consent(execution, "owner", owner.prop("partyId").stringValue())>
<#assign applicantName = (firstName!"") + " " + (lastName!"")>
<#assign confirmUrl = frontendBaseUrl + "/confirm-owner/" + ownerToken>
Hello ${ownerName},

${applicantName} has named you as a co-owner of a vehicle registration
with Transpordiamet and needs your signature before it can proceed.

Open this link to review the registration and approve or reject:
${confirmUrl}

If you reject, the case is sent back to ${applicantName} with the reason
you provide. Once every co-owner has signed, any owner can click "Send
to Transport Authority" on the confirmation page to forward the case for
review.

Thanks,
Transpordiamet POC

<#include "/email/_footer.ftl">
