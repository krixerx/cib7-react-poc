<#--
  pdf-renderer /render payload for the "Generate state fee invoice"
  service task in person-registration.bpmn.

  Renders an Estonian-styled state fee invoice for the approved vehicle
  registration. Process variables in scope: firstName, lastName, age,
  objectId (vehicle code), price (vehicle value), applicantEmail.

  The displayed state fee follows a simple tiered schedule based on
  vehicle value (POC demo — the real Transpordiamet schedule is more
  granular). The invoice references a synthesised IBAN and the process
  instance id as the payment reference, mirroring how a real SEPA
  invoice looks. Payment is not actually checked here in PR #1 — a
  future PR adds a payment receive task.

  We render the PDF body into the `html` assign block first, then
  ?json_string it inline so all embedded quotes/newlines/<> get
  escaped into a single valid JSON string. The HTML uses inline CSS
  so Gotenberg's Chromium renders it consistently without needing
  external stylesheets.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign vehicleValue = (price!0)?number>
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
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>State fee invoice</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 3rem; color: #1a1a1a; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 3px solid #003c7a; padding-bottom: 1rem; margin-bottom: 2rem; }
    .header h1 { color: #003c7a; margin: 0 0 0.25rem 0; font-size: 1.6rem; }
    .header .org { color: #555; font-size: 0.85rem; }
    .meta { text-align: right; color: #555; font-size: 0.85rem; }
    .meta strong { color: #1a1a1a; }
    h2 { color: #003c7a; margin-top: 2rem; margin-bottom: 0.5rem; font-size: 1.05rem; text-transform: uppercase; letter-spacing: 0.04em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 12rem; color: #555; font-weight: 500; }
    .amount-due { background: #f0f5fb; border-left: 4px solid #003c7a; padding: 1rem 1.25rem; margin-top: 2rem; font-size: 1.05rem; }
    .amount-due strong { color: #003c7a; font-size: 1.4rem; display: block; margin-top: 0.25rem; }
    .footer { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid #ddd; color: #888; font-size: 0.75rem; }
  </style>
</head>
<body>
  <div class="header">
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
    <tr><th>Email</th><td>${applicantEmail!"&mdash;"}</td></tr>
  </table>

  <h2>Vehicle</h2>
  <table>
    <tr><th>Vehicle code</th><td>${objectId!""}</td></tr>
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
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "state-fee-invoice-${(objectId!"vehicle")?json_string}.pdf"
}
