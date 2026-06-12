<#--
  Mailpit /api/v1/send payload for Task_RopPermitPaymentEmail in
  rop-learning-permit.bpmn. Flat 6 OMR service fee (demo's Service Fee) —
  hard-coded here AND in PaymentController for the ropLearningPermit key,
  so the email and the pay page always agree. Variables in scope:
  applicantName, applicantEmail, licenseCategory, frontendBaseUrl,
  execution.
-->
<#assign payUrl = frontendBaseUrl + "/pay/" + execution.processInstanceId>
<#assign body>Dear ${applicantName!""},

Your driving learning permit application (category ${licenseCategory!""})
has been approved pending payment.

Service fee:  6.000 OMR

Pay the fee here:
${payUrl}

After the payment is received, your electronic learning license is issued
and sent to this address together with the payment receipt.

Royal Oman Police — General Traffic Department
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Royal Oman Police — General Traffic Department" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Learning permit approved — pay the 6 OMR service fee",
  "Text": "${body?json_string}"
}
