<#--
  Email body: vehicle-sendback. Plain text; the payload template
  templates/vehicle-sendback-email.json.ftl puts it into the Mailpit JSON.
-->
Hi ${firstName!""},

The Transport Authority has sent your vehicle registration back for
corrections.

Reason: ${sendBackReason!""}

Open the My processes page in the portal and resubmit when ready.

Thanks,
Transpordiamet POC

<#include "/email/_footer.ftl">
