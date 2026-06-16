<#--
  pdf-renderer /render payload for Task_TransportPermitPdf in
  transport-learning-permit.bpmn. One document carries the electronic learning
  license AND the payment receipt (demo: "Send notification of receipt of
  electronic driving license and payment receipt"). Variables in scope:
  applicantName, civilId, age, licenseCategory, permitNumber,
  permitValidUntil, execution.
-->
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Driving Learning License</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 2.5rem; color: #1a1a1a; }
    .border { border: 2px solid #7a0019; padding: 2.5rem 2.5rem 2rem; border-radius: 6px; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #ddd; padding-bottom: 1rem; margin-bottom: 1.5rem; }
    .header h1 { color: #7a0019; margin: 0 0 0.25rem 0; font-size: 1.5rem; }
    .header .org { color: #555; font-size: 0.85rem; }
    .header .permitno { text-align: right; color: #555; font-size: 0.85rem; }
    .header .permitno strong { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #7a0019; font-size: 1.05rem; display: block; }
    h2 { color: #7a0019; margin-top: 1.5rem; margin-bottom: 0.4rem; font-size: 0.95rem; text-transform: uppercase; letter-spacing: 0.05em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.35rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 12rem; color: #555; font-weight: 500; }
    .receipt { margin-top: 1.5rem; background: #f7f3f4; border: 1px solid #e3d4d8; border-radius: 6px; padding: 1rem 1.25rem; }
    .receipt h2 { margin-top: 0; }
    .stamp { margin-top: 2rem; padding-top: 1rem; border-top: 1px solid #ddd; display: flex; justify-content: space-between; color: #666; font-size: 0.8rem; }
    .stamp .ref { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  </style>
</head>
<body>
  <div class="border">
    <div class="header">
      <div>
        <h1>Driving Learning License</h1>
        <div class="org">Transport Authority</div>
      </div>
      <div class="permitno">
        Permit no.
        <strong>${(permitNumber!"-")?html}</strong>
      </div>
    </div>

    <p style="font-size: 0.95rem; line-height: 1.5; margin-top: 0;">
      This electronic license authorizes the holder named below to learn to
      drive vehicles of the stated category under the supervision of a
      licensed driving instructor, the demo jurisdiction.
    </p>

    <h2>License holder</h2>
    <table>
      <tr><th>Full name</th><td>${(applicantName!"")?html}</td></tr>
      <tr><th>Civil number</th><td>${(civilId!"")?html}</td></tr>
      <tr><th>Age</th><td>${(age!0)}</td></tr>
      <tr><th>License category</th><td>${(licenseCategory!"")?html}</td></tr>
      <tr><th>Valid until</th><td>${(permitValidUntil!"")?html}</td></tr>
    </table>

    <div class="receipt">
      <h2>Payment receipt</h2>
      <table>
        <tr><th>Service</th><td>Issuing a driving learning license</td></tr>
        <tr><th>Service fee</th><td>6.000 EUR &mdash; paid</td></tr>
        <tr><th>Reference</th><td>${execution.processInstanceId}</td></tr>
      </table>
    </div>

    <div class="stamp">
      <div>Issued on: <span class="ref">${.now?string("yyyy-MM-dd")}</span></div>
      <div>Case reference: <span class="ref">${execution.processInstanceId}</span></div>
    </div>
  </div>
</body>
</html>
</#assign>
{
  "html": "${html?json_string}",
  "filename": "transport-learning-permit-${execution.processInstanceId?json_string}.pdf"
}
