<#--
  Mailpit /api/v1/send payload for Task_TransportPermitEmail in
  transport-learning-permit.bpmn. Demo step 5 — electronic license + payment
  receipt, attached as one PDF. Variables in scope: applicantName,
  applicantEmail, permitNumber, permitValidUntil, licenseCategory,
  permitPdfBytes, permitPdfFilename.
-->
<#assign body>Dear ${applicantName!""},

Congratulations — your driving learning license has been issued.

Permit number:  ${permitNumber!""}
Category:       ${licenseCategory!""}
Valid until:    ${permitValidUntil!""}

The attached PDF is your electronic learning license together with the
payment receipt. Keep it with you during driving lessons.

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Your driving learning license ${(permitNumber!"")?json_string}",
  "Text": "${body?json_string}",
  "Attachments": [
    {
      "Filename": "${(permitPdfFilename!"transport-learning-permit.pdf")?json_string}",
      "ContentType": "application/pdf",
      "Content": "${pdf.encode(permitPdfBytes)}"
    }
  ]
}
