<#--
  pdf-renderer /render payload for Task_GenerateBcardPdf in
  business-registration.bpmn.

  Renders an Äriregister-styled B-card extract (väljavõte B-kaardilt)
  for every approved OÜ — auto-approved and Business Register approved
  alike.

  Process variables in scope: companyName, shareCapital, applicantFirstName,
  applicantLastName, applicantAge, applicantResidency (one of citizen /
  e-resident / foreign), boardMembers (Spin Json list), additionalFounders
  (Spin Json list, may be null), initiator, plus the standard execution
  properties.

  shareCapital coerced defensively (?is_number guard + replace-and-parse
  fallback) because some engine→JUEL paths surface numeric process
  variables as locale-formatted Strings like "2,500". See the matching
  memory entry [[cib7-freemarker-numeric-vars-defensive]].

  Same shape as personReg's certificate-pdf — HTML body rendered into
  the `html` assign block first, then ?json_string'd inline.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign reference = execution.processInstanceId>
<#assign regCode = "1" + reference?replace("-", "")?substring(0, 7)>
<#assign rawCapital = (shareCapital!0)>
<#if rawCapital?is_number>
  <#assign capitalNumber = rawCapital>
<#else>
  <#assign capitalNumber = rawCapital?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
<#assign hasFounders = additionalFounders?? && additionalFounders.elements()?size gt 0>
<#assign residencyRaw = (applicantResidency!"citizen")>
<#if residencyRaw == "e-resident">
  <#assign residencyLabel = "E-resident">
<#elseif residencyRaw == "foreign">
  <#assign residencyLabel = "Foreign founder">
<#else>
  <#assign residencyLabel = "Estonian citizen">
</#if>
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>B-card extract</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 2.5rem; color: #1a1a1a; }
    .border { border: 2px solid #0b6e4f; padding: 2.5rem 2.5rem 2rem; border-radius: 6px; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #ddd; padding-bottom: 1rem; margin-bottom: 1.5rem; }
    .header h1 { color: #0b6e4f; margin: 0 0 0.25rem 0; font-size: 1.5rem; }
    .header .org { color: #555; font-size: 0.85rem; }
    .header .regno { text-align: right; color: #555; font-size: 0.85rem; }
    .header .regno strong { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #0b6e4f; font-size: 1.05rem; display: block; }
    .lead { font-size: 0.95rem; line-height: 1.5; margin-top: 0; }
    h2 { color: #0b6e4f; margin-top: 1.5rem; margin-bottom: 0.4rem; font-size: 0.95rem; text-transform: uppercase; letter-spacing: 0.05em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.35rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 11rem; color: #555; font-weight: 500; }
    ul.party-list { margin: 0; padding-left: 1.25rem; }
    ul.party-list li { padding: 0.2rem 0; font-size: 0.95rem; }
    .stamp { margin-top: 2rem; padding-top: 1rem; border-top: 1px solid #ddd; display: flex; justify-content: space-between; color: #666; font-size: 0.8rem; }
    .stamp .ref { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  </style>
</head>
<body>
  <div class="border">
    <div class="header">
      <div>
        <h1>B-card extract (B-kaart)</h1>
        <div class="org">Äriregister &mdash; Estonian Business Register</div>
      </div>
      <div class="regno">
        Registration code
        <strong>${regCode}</strong>
      </div>
    </div>

    <p class="lead">
      The Estonian Business Register confirms that the company identified
      below is entered in the register as an osaühing (private limited
      company) with the share capital and management board listed.
    </p>

    <h2>Company</h2>
    <table>
      <tr><th>Name</th><td>${companyName!""}</td></tr>
      <tr><th>Form</th><td>Osaühing (OÜ)</td></tr>
      <tr><th>Share capital</th><td>&euro;${capitalNumber?string("0.00")}</td></tr>
    </table>

    <h2>Management board</h2>
    <ul class="party-list">
      <#list boardMembers.elements() as m>
        <li>${(m.prop("firstName").stringValue())!""} ${(m.prop("lastName").stringValue())!""}
            <span style="color: #666">(isikukood ${(m.prop("personalCode").stringValue())!""})</span></li>
      </#list>
    </ul>

    <h2>Founder<#if hasFounders>s</#if></h2>
    <ul class="party-list">
      <li>${fullName}
          <span style="color: #666">&middot; applicant &middot; ${residencyLabel}</span></li>
      <#if hasFounders>
        <#list additionalFounders.elements() as f>
          <li>${f.prop("name").stringValue()}</li>
        </#list>
      </#if>
    </ul>

    <div class="stamp">
      <div>Issued by: <span class="ref">Äriregister POC</span></div>
      <div>Process instance: <span class="ref">${reference}</span></div>
    </div>
  </div>
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "bcard-extract-${(companyName!"OU")?json_string}.pdf"
}
