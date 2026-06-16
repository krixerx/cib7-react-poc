<#--
  Mailpit /api/v1/send payload for Task_TransportPermitPaymentEmail in
  transport-learning-permit.bpmn. Flat 6 EUR service fee (demo's Service Fee) —
  hard-coded here AND in PaymentController for the transportLearningPermit key,
  so the email and the pay page always agree. Variables in scope:
  applicantName, applicantEmail, licenseCategory, frontendBaseUrl,
  execution.
-->
<#assign payUrl = frontendBaseUrl + "/pay/" + execution.processInstanceId>
<#assign body>Dear ${applicantName!""},

Your driving learning permit application (category ${licenseCategory!""})
has been approved pending payment.

Service fee:  6.000 EUR

Pay the fee here:
${payUrl}

After the payment is received, your electronic learning license is issued
and sent to this address together with the payment receipt.

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Learning permit approved — pay the 6 EUR service fee",
  "Text": "${body?json_string}"
}
