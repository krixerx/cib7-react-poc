<#--
  pdf-renderer /render payload for Task_GenerateFeeInvoicePdf in
  business-registration.bpmn.

  Renders an Äriregister-styled state fee invoice for the approved OÜ
  registration. Process variables in scope: companyName, shareCapital,
  applicantFirstName, applicantLastName, applicantEmail, autoDecision,
  decision, plus the execution properties.

  The displayed state fee is the standard Estonian fast-track fee for OÜ
  founding (€265 — flat, regardless of share capital). The invoice
  references a synthesised IBAN and the process instance id as the
  payment reference so the demo viewer recognises it as a real SEPA
  invoice. Payment is not actually checked here in PR #5 — a future PR
  adds the shared payment receive task.

  Same shape as state-fee-invoice — HTML body rendered into the `html`
  assign block first, then ?json_string'd inline so embedded quotes /
  newlines / <> are escaped into a single valid JSON string. Inline CSS
  so Gotenberg's Chromium renders consistently without external sheets.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign reference = execution.processInstanceId>
<#assign regCode = "1" + reference?replace("-", "")?substring(0, 7)>
<#assign fee = 265>
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>State fee invoice</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 3rem; color: #1a1a1a; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 3px solid #0b6e4f; padding-bottom: 1rem; margin-bottom: 2rem; }
    .header h1 { color: #0b6e4f; margin: 0 0 0.25rem 0; font-size: 1.6rem; }
    .header .org { color: #555; font-size: 0.85rem; }
    .meta { text-align: right; color: #555; font-size: 0.85rem; }
    .meta strong { color: #1a1a1a; }
    h2 { color: #0b6e4f; margin-top: 2rem; margin-bottom: 0.5rem; font-size: 1.05rem; text-transform: uppercase; letter-spacing: 0.04em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 12rem; color: #555; font-weight: 500; }
    .amount-due { background: #eef7f2; border-left: 4px solid #0b6e4f; padding: 1rem 1.25rem; margin-top: 2rem; font-size: 1.05rem; }
    .amount-due strong { color: #0b6e4f; font-size: 1.4rem; display: block; margin-top: 0.25rem; }
    .footer { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid #ddd; color: #888; font-size: 0.75rem; }
  </style>
</head>
<body>
  <div class="header">
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
    <tr><th>Email</th><td>${applicantEmail!"&mdash;"}</td></tr>
  </table>

  <h2>Company</h2>
  <table>
    <tr><th>Company name</th><td>${companyName!""}</td></tr>
    <tr><th>Registration code</th><td>${regCode}</td></tr>
    <tr><th>Share capital</th><td>&euro;${(shareCapital!0.0)?string("0.00")}</td></tr>
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
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "state-fee-invoice-${(companyName!"OU")?json_string}.pdf"
}
