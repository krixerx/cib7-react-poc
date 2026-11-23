<#--
  Email body: vehicle-approval. Plain text; the payload template templates/approval-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign payUrl = frontendBaseUrl + "/pay/" + links.payment(execution)>
<#-- price can surface as a locale-formatted String ("38,000") on some
     engine→FreeMarker paths — same defensive coercion as approval-pdf. -->
<#assign rawPrice = (price!0)>
<#if rawPrice?is_number>
  <#assign vehicleValue = rawPrice>
<#else>
  <#assign vehicleValue = rawPrice?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
Hi ${firstName!""},

Your vehicle registration with Transpordiamet has been approved. The
State fee invoice is attached — pay the listed amount to complete the
registration. Once the payment is received, your Vehicle Registration
Certificate (tehniline pass) will be issued.

Owner: ${fullName}
Vehicle code: ${objectId!""}
Vehicle value: €${vehicleValue?string("0.00")}

Pay the state fee here:
${payUrl}

Thanks,
Transpordiamet POC

<#include "/email/_footer.ftl">
