<#--
  Email body: business-approval. Plain text; the payload template templates/business-approval-email.json.ftl
  puts it into the Mailpit JSON. Sees the case variables, execution,
  frontendBaseUrl, links and brand (DocumentRenderer).
-->
<#assign caseRef = execution.processInstanceId>
<#assign regCode = "1" + caseRef?replace("-", "")?substring(0, 7)>
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign members>
<#list boardMembers.elements() as m>
- ${(m.prop("firstName").stringValue())!""} ${(m.prop("lastName").stringValue())!""} (isikukood ${(m.prop("personalCode").stringValue())!""})
</#list>
</#assign>
<#-- shareCapital can surface as a locale-formatted String ("2,500") on some
     engine→FreeMarker paths — same defensive coercion as bcard-extract.ftlh. -->
<#assign rawCapital = (shareCapital!0)>
<#if rawCapital?is_number>
  <#assign capitalNumber = rawCapital>
<#else>
  <#assign capitalNumber = rawCapital?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
<#assign residencyRaw = (applicantResidency!"citizen")>
<#if residencyRaw == "e-resident">
  <#assign residencyLabel = "e-resident">
<#elseif residencyRaw == "foreign">
  <#assign residencyLabel = "foreign founder">
<#else>
  <#assign residencyLabel = "Estonian citizen">
</#if>
Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your Estonian OÜ has been approved by the Business Register and the
state fee invoice is attached. Pay the listed amount and your B-card
extract will be issued.

Business Register code:  ${regCode}
Company:                 ${(companyName!"")}
Share capital:           ${capitalNumber?string("0.00")} EUR
Founder residency:       ${residencyLabel}
Board members:
${members}
Decision made by:        ${((autoDecision!"")=="approve")?then("automated decision table (DMN)", "manual review by the Business Register")}

Pay the state fee here:
${frontendBaseUrl}/pay/${links.payment(execution)}

Once payment is received, the B-card extract will be available in the My
processes page in the SPA.

Tervitustega,
Äriregister POC

<#include "/email/_footer.ftl">
