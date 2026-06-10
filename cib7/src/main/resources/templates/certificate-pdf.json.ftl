<#--
  pdf-renderer /render payload for Task_GenerateCertificatePdf
  in person-registration.bpmn.

  Renders a Vehicle Registration Certificate (Estonian tehniline pass
  styled, POC) for every approved case — auto-approved and Transport
  Authority approved alike.

  Process variables in scope: firstName, lastName, age, objectId
  (VIN), price (vehicle value), initiator, additionalOwners (Spin Json
  list of co-owners, may be null), plus the vehicle-registry fields
  written by Task_GetPrice (vehicleMake, vehicleModel, vehicleYear,
  vehicleFuelType, vehicleAgeYears) and the standard execution
  properties.

  Same shape as state-fee-invoice — HTML body rendered into the `html`
  assign block first, then ?json_string'd inline.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign hasCoOwners = additionalOwners?? && additionalOwners.elements()?size gt 0>
<#assign certificateNumber = "EE" + execution.processInstanceId?substring(0, 8)?upper_case>
<#-- Same defensive coercion as approval-pdf.json.ftl — see the
     comment there for the rationale. -->
<#assign rawPrice = (price!0)>
<#if rawPrice?is_number>
  <#assign vehicleValue = rawPrice>
<#else>
  <#assign vehicleValue = rawPrice?replace(",", "")?replace(" ", "")?replace(" ", "")?replace("$", "")?replace("€", "")?number>
</#if>
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Vehicle Registration Certificate</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 2.5rem; color: #1a1a1a; }
    .border { border: 2px solid #003c7a; padding: 2.5rem 2.5rem 2rem; border-radius: 6px; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #ddd; padding-bottom: 1rem; margin-bottom: 1.5rem; }
    .header h1 { color: #003c7a; margin: 0 0 0.25rem 0; font-size: 1.5rem; }
    .header .org { color: #555; font-size: 0.85rem; }
    .header .certno { text-align: right; color: #555; font-size: 0.85rem; }
    .header .certno strong { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #003c7a; font-size: 1.05rem; display: block; }
    h2 { color: #003c7a; margin-top: 1.5rem; margin-bottom: 0.4rem; font-size: 0.95rem; text-transform: uppercase; letter-spacing: 0.05em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.35rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 11rem; color: #555; font-weight: 500; }
    ul.coowners { margin: 0; padding-left: 1.25rem; }
    ul.coowners li { padding: 0.2rem 0; font-size: 0.95rem; }
    .stamp { margin-top: 2rem; padding-top: 1rem; border-top: 1px solid #ddd; display: flex; justify-content: space-between; color: #666; font-size: 0.8rem; }
    .stamp .ref { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  </style>
</head>
<body>
  <div class="border">
    <div class="header">
      <div>
        <h1>Vehicle Registration Certificate</h1>
        <div class="org">Transpordiamet &mdash; Estonian Transport Authority</div>
      </div>
      <div class="certno">
        Certificate no.
        <strong>${certificateNumber}</strong>
      </div>
    </div>

    <p style="font-size: 0.95rem; line-height: 1.5; margin-top: 0;">
      This certifies that the vehicle identified below has been entered in
      the Estonian Traffic Register in the name of the registered owner.
    </p>

    <h2>Vehicle</h2>
    <table>
      <tr><th>Make &amp; model</th><td>${vehicleMake!""} ${vehicleModel!""}</td></tr>
      <tr><th>Year of make</th><td>${(vehicleYear!0)}</td></tr>
      <tr><th>Fuel type</th><td>${vehicleFuelType!"&mdash;"}</td></tr>
      <tr><th>VIN</th><td>${objectId!""}</td></tr>
      <tr><th>Declared value</th><td>&euro;${vehicleValue?string("0.00")}</td></tr>
    </table>

    <h2>Registered owner</h2>
    <table>
      <tr><th>Full name</th><td>${fullName}</td></tr>
      <tr><th>Age</th><td>${(age!0)}</td></tr>
    </table>

    <#if hasCoOwners>
    <h2>Co-owners</h2>
    <ul class="coowners">
      <#list additionalOwners.elements() as owner>
        <li>${owner.prop("name").stringValue()}</li>
      </#list>
    </ul>
    </#if>

    <div class="stamp">
      <div>Initiator: <span class="ref">${initiator!"unknown"}</span></div>
      <div>Process instance: <span class="ref">${execution.processInstanceId}</span></div>
    </div>
  </div>
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "vehicle-registration-certificate-${execution.processInstanceId?json_string}.pdf"
}
