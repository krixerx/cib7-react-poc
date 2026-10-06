<#--
  PDF document: bcard-extract, rendered to PDF by pdf-renderer through the payload
  template templates/bcard-pdf.json.ftl. Layout and house style come from
  /_brand.ftlh; values are HTML-escaped automatically (.ftlh).
-->
<#import "/_brand.ftlh" as b>
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
<@b.page title="B-card extract" accent="#0b6e4f">
  <div class="border">
    <div class="header">
      <div>
        <h1>B-card extract (B-kaart)</h1>
        <div class="org">Äriregister &mdash; Estonian Business Register</div>
      </div>
      <div class="meta">
        Registration code
        <strong class="code">${regCode}</strong>
      </div>
    </div>

    <p class="lead">
      The Estonian Business Register confirms that the company identified
      below is entered in the register as an osaühing (private limited
      company) with the share capital and management board listed.
    </p>

    <h2>Company</h2>
    <table>
      <tr><th>Name</th><td>${(companyName!"")}</td></tr>
      <tr><th>Form</th><td>Osaühing (OÜ)</td></tr>
      <tr><th>Share capital</th><td>&euro;${capitalNumber?string("0.00")}</td></tr>
    </table>

    <h2>Management board</h2>
    <ul class="party-list">
      <#list boardMembers.elements() as m>
        <li>${((m.prop("firstName").stringValue())!"")} ${((m.prop("lastName").stringValue())!"")}
            <span class="muted">(isikukood ${((m.prop("personalCode").stringValue())!"")})</span></li>
      </#list>
    </ul>

    <h2>Founder<#if hasFounders>s</#if></h2>
    <ul class="party-list">
      <li>${fullName}
          <span class="muted">&middot; applicant &middot; ${residencyLabel}</span></li>
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
</@b.page>
