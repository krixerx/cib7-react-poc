<#--
  PDF document: business-fee-invoice, rendered to PDF by pdf-renderer through the payload
  template templates/business-fee-invoice-pdf.json.ftl. Layout and house style come from
  /_brand.ftlh; values are HTML-escaped automatically (.ftlh).
-->
<#import "/_brand.ftlh" as b>
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign reference = execution.processInstanceId>
<#assign regCode = "1" + reference?replace("-", "")?substring(0, 7)>
<#assign fee = 265>
<#-- shareCapital can surface as a locale-formatted String ("2,500") on some
     engine→FreeMarker paths — same defensive coercion as bcard-extract.ftlh, which
     ?string("0.00") on the raw variable would crash on. -->
<#assign rawCapital = (shareCapital!0)>
<#if rawCapital?is_number>
  <#assign capitalNumber = rawCapital>
<#else>
  <#assign capitalNumber = rawCapital?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
<@b.page title="State fee invoice" accent="#0b6e4f">
  <div class="header rule">
    <div>
      <h1>State fee invoice</h1>
      <div class="org">Äriregister &mdash; Estonian Business Register</div>
    </div>
    <div class="meta">
      Invoice: <strong>RF-${reference?substring(0, 8)?upper_case}</strong><br/>
      Service: OÜ registration (fast-track)<br/>
      Status: <strong>Awaiting payment</strong>
    </div>
  </div>

  <h2>Founder</h2>
  <table>
    <tr><th>Full name</th><td>${fullName}</td></tr>
    <tr><th>Email</th><td><#if applicantEmail?has_content>${applicantEmail}<#else>&mdash;</#if></td></tr>
  </table>

  <h2>Company</h2>
  <table>
    <tr><th>Company name</th><td>${(companyName!"")}</td></tr>
    <tr><th>Registration code</th><td>${regCode}</td></tr>
    <tr><th>Share capital</th><td>&euro;${capitalNumber?string("0.00")}</td></tr>
  </table>

  <h2>Payment details</h2>
  <table>
    <tr><th>Recipient</th><td>Äriregister (Justiitsministeerium)</td></tr>
    <tr><th>IBAN</th><td>EE76 1010 2200 2401 4115</td></tr>
    <tr><th>Reference number</th><td>${reference}</td></tr>
  </table>

  <div class="amount-due">
    Amount due
    <strong>&euro;${fee?string("0.00")}</strong>
  </div>

  <div class="footer">
    Auto-generated. POC document &mdash; no signature required. Process instance: ${reference}.
  </div>
</@b.page>
