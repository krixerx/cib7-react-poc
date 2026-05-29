<#--
  Mailpit /api/v1/send payload for the "Send approval email" service task in
  person-registration.bpmn. All process variables of the executing scope are
  in scope: firstName, lastName, age, objectId, price, applicantEmail, and —
  written by the preceding "Generate approval PDF" task — approvalPdfBytes
  (raw PDF bytes, stored in ACT_GE_BYTEARRAY) and approvalPdfFilename.

  The BPMN gateway guarantees applicantEmail is non-null and contains '@'
  before this template runs, but we still null-default to keep the template
  robust if it is ever invoked from a different path. ?json_string escapes
  embedded quotes/backslashes/newlines so the resulting payload is always
  valid JSON. We base64-encode the PDF bytes here (rather than carrying a
  base64 String process variable) because String variables in CIB seven cap
  at 4000 chars in ACT_HI_VARINST.TEXT_.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign body>Hi ${firstName!""},

Your application has been approved. A signed PDF copy is attached.

Applicant: ${fullName}
Product ID: ${objectId!""}
Price: ${(price!0)?string("0.00")}

Thanks,
CIB7 POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your application has been approved",
  "Text": "${body?json_string}",
  "Attachments": [
    {
      "Filename": "${(approvalPdfFilename!"approval.pdf")?json_string}",
      "ContentType": "application/pdf",
      "Content": "${pdf.encode(approvalPdfBytes)}"
    }
  ]
}
