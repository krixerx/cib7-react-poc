<#--
  PDF document: vehicle-certificate, rendered to PDF by pdf-renderer through the payload
  template templates/certificate-pdf.json.ftl. Layout and house style come from
  /_brand.ftlh; values are HTML-escaped automatically (.ftlh).
-->
<#import "/_brand.ftlh" as b>
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign hasCoOwners = additionalOwners?? && additionalOwners.elements()?size gt 0>
<#assign certificateNumber = "EE" + execution.processInstanceId?substring(0, 8)?upper_case>
<#-- Same defensive coercion as vehicle-fee-invoice.ftlh — see the
     comment there for the rationale. -->
<#assign rawPrice = (price!0)>
<#if rawPrice?is_number>
  <#assign vehicleValue = rawPrice>
<#else>
  <#assign vehicleValue = rawPrice?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
<@b.page title="Vehicle Registration Certificate" accent="#003c7a">
  <div class="border">
    <div class="header">
      <div>
        <h1>Vehicle Registration Certificate</h1>
        <div class="org">Transpordiamet &mdash; Estonian Transport Authority</div>
      </div>
      <div class="meta">
        Certificate no.
        <strong class="code">${certificateNumber}</strong>
      </div>
    </div>

    <p class="lead">
      This certifies that the vehicle identified below has been entered in
      the Estonian Traffic Register in the name of the registered owner.
    </p>

    <h2>Vehicle</h2>
    <table>
      <tr><th>Make &amp; model</th><td>${(vehicleMake!"")} ${(vehicleModel!"")}</td></tr>
      <tr><th>Year of make</th><td>${(vehicleYear!0)}</td></tr>
      <tr><th>Fuel type</th><td><#if vehicleFuelType?has_content>${vehicleFuelType}<#else>&mdash;</#if></td></tr>
      <tr><th>VIN</th><td>${(objectId!"")}</td></tr>
      <tr><th>Declared value</th><td>&euro;${vehicleValue?string("0.00")}</td></tr>
    </table>

    <h2>Registered owner</h2>
    <table>
      <tr><th>Full name</th><td>${fullName}</td></tr>
      <tr><th>Age</th><td>${(age!0)}</td></tr>
    </table>

    <#if hasCoOwners>
    <h2>Co-owners</h2>
    <ul class="party-list">
      <#list additionalOwners.elements() as owner>
        <li>${owner.prop("name").stringValue()}</li>
      </#list>
    </ul>
    </#if>

    <div class="stamp">
      <div>Initiator: <span class="ref">${(initiator!"unknown")}</span></div>
      <div>Process instance: <span class="ref">${execution.processInstanceId}</span></div>
    </div>
  </div>
</@b.page>
