<#--
  PDF document: vehicle-fee-invoice, rendered to PDF by pdf-renderer through the payload
  template templates/approval-pdf.json.ftl. Layout and house style come from
  /_brand.ftlh; values are HTML-escaped automatically (.ftlh).
-->
<#import "/_brand.ftlh" as b>
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#-- `price` is written by Task_GetPrice via a JUEL output mapping. It
     should be a Double, but cases driven by the legacy restful-api.dev
     stub or a locale-formatting quirk in the engine's variable
     serializer have been observed arriving as a String like "38,000".
     Defend against both shapes: number → use as-is; string → strip
     thousands separators (comma, space, NBSP) and currency symbols
     before parsing. -->
<#assign rawPrice = (price!0)>
<#if rawPrice?is_number>
  <#assign vehicleValue = rawPrice>
<#else>
  <#assign vehicleValue = rawPrice?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
<#-- Tiered state fee schedule. POC demo — Transpordiamet's real schedule
     is more granular and varies by CO2, fuel type, and first-registration
     status. The brackets here are picked to be visibly different across
     the demo's vehicle catalog. -->
<#if vehicleValue lt 5000>
  <#assign fee = 25>
<#elseif vehicleValue lt 20000>
  <#assign fee = 75>
<#else>
  <#assign fee = 150>
</#if>
<#assign reference = execution.processInstanceId>
<@b.page title="State fee invoice" accent="#003c7a">
  <div class="header rule">
    <div>
      <h1>State fee invoice</h1>
      <div class="org">Transpordiamet &mdash; Estonian Transport Authority</div>
    </div>
    <div class="meta">
      Invoice: <strong>SF-${reference?substring(0, 8)?upper_case}</strong><br/>
      Service: Vehicle registration<br/>
      Status: <strong>Awaiting payment</strong>
    </div>
  </div>

  <h2>Registered owner</h2>
  <table>
    <tr><th>Full name</th><td>${fullName}</td></tr>
    <tr><th>Age</th><td>${(age!0)}</td></tr>
    <tr><th>Email</th><td><#if applicantEmail?has_content>${applicantEmail}<#else>&mdash;</#if></td></tr>
  </table>

  <h2>Vehicle</h2>
  <table>
    <tr><th>Make &amp; model</th><td>${(vehicleMake!"")} ${(vehicleModel!"")}</td></tr>
    <tr><th>Year</th><td>${(vehicleYear!0)} <#if (vehicleAgeYears!0) gt 0>(${vehicleAgeYears} years old)</#if></td></tr>
    <tr><th>Fuel type</th><td><#if vehicleFuelType?has_content>${vehicleFuelType}<#else>&mdash;</#if></td></tr>
    <tr><th>VIN</th><td>${(objectId!"")}</td></tr>
    <tr><th>Declared value</th><td>&euro;${vehicleValue?string("0.00")}</td></tr>
  </table>

  <h2>Payment details</h2>
  <table>
    <tr><th>Recipient</th><td>Transpordiamet</td></tr>
    <tr><th>IBAN</th><td>EE89 3300 3334 1110 3007</td></tr>
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
