<#--
  pdf-renderer /render payload for the "Generate approval PDF" service task
  in person-registration.bpmn. Process variables in scope: firstName, lastName,
  age, objectId, price, applicantEmail (same as approval-email.json.ftl).

  We render the PDF body into the `html` assign block first, then ?json_string
  it inline so all embedded quotes/newlines/<> get escaped into a single valid
  JSON string. The HTML uses inline CSS so Gotenberg's Chromium renders it
  consistently without needing external stylesheets.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Approval certificate</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 4rem; color: #1a1a1a; }
    h1 { color: #0b6e4f; margin-bottom: 0.25rem; }
    .meta { color: #666; font-size: 0.9rem; margin-bottom: 2rem; }
    table { border-collapse: collapse; width: 100%; max-width: 32rem; }
    th, td { text-align: left; padding: 0.5rem 0.75rem; border-bottom: 1px solid #eee; }
    th { width: 10rem; color: #444; font-weight: 600; }
    .footer { margin-top: 3rem; color: #888; font-size: 0.8rem; }
  </style>
</head>
<body>
  <h1>Application approved</h1>
  <div class="meta">CIB7 POC &mdash; person registration</div>

  <table>
    <tr><th>Applicant</th><td>${fullName}</td></tr>
    <tr><th>Age</th>      <td>${(age!0)}</td></tr>
    <tr><th>Product ID</th><td>${objectId!""}</td></tr>
    <tr><th>Price</th>    <td>${(price!0)?string("0.00")}</td></tr>
    <tr><th>Email</th>    <td>${applicantEmail!""}</td></tr>
  </table>

  <p class="footer">This document is auto-generated. No signature required.</p>
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "approval-${(objectId!"document")?json_string}.pdf"
}
