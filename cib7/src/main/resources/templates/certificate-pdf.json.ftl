<#--
  pdf-renderer /render payload for Task_GenerateCertificatePdf. Same
  shape as approval-pdf.json.ftl — HTML body rendered into the `html`
  assign block first, then ?json_string'd inline. Produces a
  short certificate document fit to download from the Documents card.

  Process variables in scope: firstName, lastName, objectId, price,
  initiator, plus the standard execution properties.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Certificate of approval</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 4rem; color: #1a1a1a; }
    .border { border: 2px solid #0b6e4f; padding: 3rem; border-radius: 8px; }
    h1 { color: #0b6e4f; margin: 0 0 0.5rem 0; font-size: 2rem; }
    .subtitle { color: #555; font-size: 0.95rem; margin-bottom: 2.5rem; }
    .body { font-size: 1.05rem; line-height: 1.6; }
    .body strong { color: #0b6e4f; }
    .stamp { margin-top: 3rem; padding-top: 1.5rem; border-top: 1px solid #ddd;
             color: #666; font-size: 0.85rem; }
    .stamp .ref { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  </style>
</head>
<body>
  <div class="border">
    <h1>Certificate of Approval</h1>
    <div class="subtitle">CIB7 POC &mdash; person registration</div>
    <div class="body">
      <p>This certifies that the application of <strong>${fullName}</strong>
      has been reviewed and approved for product <strong>${objectId!""}</strong>
      at a price of <strong>${(price!0)?string("0.00")}</strong>.</p>
      <p>This certificate may be downloaded by the applicant or by any
      member of the back office with access to the case.</p>
    </div>
    <div class="stamp">
      Process instance: <span class="ref">${execution.processInstanceId}</span><br/>
      Initiator: <span class="ref">${initiator!"unknown"}</span>
    </div>
  </div>
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "certificate-${execution.processInstanceId?json_string}.pdf"
}
