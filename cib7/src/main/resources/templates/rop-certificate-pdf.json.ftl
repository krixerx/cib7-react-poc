<#--
  pdf-renderer /render payload for Task_RopCertificatePdf in
  rop-vehicle-registration.bpmn. Renders the ROP Vehicle Registration
  Certificate after the fee is paid and the plate is allocated.
  Variables in scope: applicantName, civilId, vin, vehicleCategory,
  registrationType, plateNumber, registrationFee, execution.
-->
<#assign rawFee = (registrationFee!0)>
<#if rawFee?is_number>
  <#assign feeAmount = rawFee>
<#else>
  <#assign feeAmount = rawFee?replace(",", "")?replace(" ", "")?replace(" ", "")?number>
</#if>
<#assign certificateNumber = "OM" + execution.processInstanceId?substring(0, 8)?upper_case>
<#assign html>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Vehicle Registration Certificate</title>
  <style>
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 2.5rem; color: #1a1a1a; }
    .border { border: 2px solid #7a0019; padding: 2.5rem 2.5rem 2rem; border-radius: 6px; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #ddd; padding-bottom: 1rem; margin-bottom: 1.5rem; }
    .header h1 { color: #7a0019; margin: 0 0 0.25rem 0; font-size: 1.5rem; }
    .header .org { color: #555; font-size: 0.85rem; }
    .header .certno { text-align: right; color: #555; font-size: 0.85rem; }
    .header .certno strong { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #7a0019; font-size: 1.05rem; display: block; }
    .plate { text-align: center; margin: 1.5rem 0; }
    .plate span { display: inline-block; border: 3px solid #1a1a1a; border-radius: 8px; padding: 0.5rem 2rem; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 2rem; letter-spacing: 0.15em; }
    h2 { color: #7a0019; margin-top: 1.5rem; margin-bottom: 0.4rem; font-size: 0.95rem; text-transform: uppercase; letter-spacing: 0.05em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.35rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 12rem; color: #555; font-weight: 500; }
    .stamp { margin-top: 2rem; padding-top: 1rem; border-top: 1px solid #ddd; display: flex; justify-content: space-between; color: #666; font-size: 0.8rem; }
    .stamp .ref { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  </style>
</head>
<body>
  <div class="border">
    <div class="header">
      <div>
        <h1>Vehicle Registration Certificate</h1>
        <div class="org">Royal Oman Police &mdash; General Traffic Department</div>
      </div>
      <div class="certno">
        Certificate no.
        <strong>${certificateNumber}</strong>
      </div>
    </div>

    <p style="font-size: 0.95rem; line-height: 1.5; margin-top: 0;">
      This certifies that the vehicle identified below has been entered in
      the register of the General Traffic Department, Sultanate of Oman.
    </p>

    <div class="plate"><span>${(plateNumber!"-")?html}</span></div>

    <h2>Vehicle</h2>
    <table>
      <tr><th>Chassis number (VIN)</th><td>${(vin!"")?html}</td></tr>
      <tr><th>Category</th><td>${(vehicleCategory!"")?html}</td></tr>
      <tr><th>Registration type</th><td>${(registrationType!"")?html}</td></tr>
      <tr><th>Registration fee paid</th><td>${feeAmount?string("0.000")} OMR</td></tr>
    </table>

    <h2>Registered owner</h2>
    <table>
      <tr><th>Full name</th><td>${(applicantName!"")?html}</td></tr>
      <tr><th>Civil number</th><td>${(civilId!"")?html}</td></tr>
    </table>

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
  "filename": "rop-registration-certificate-${execution.processInstanceId?json_string}.pdf"
}
