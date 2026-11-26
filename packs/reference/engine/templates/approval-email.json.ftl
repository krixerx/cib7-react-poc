<#--
  mail-send payload (Patchbay integration, v1) for the "Send state fee invoice email"
  service task in vehicle-registration.bpmn. Sent when the applicant
  provided an email address; attaches the State fee invoice PDF
  produced by the preceding "Generate state fee invoice" task.

  Variables in scope: firstName, lastName, age, objectId (vehicle code),
  price (vehicle value), applicantEmail, approvalPdfBytes
  (raw PDF bytes — the state fee invoice), approvalPdfFilename.

  The BPMN gateway guarantees applicantEmail is non-null and contains '@'
  before this template runs, but we still null-default to keep the
  template robust if invoked from a different path. ?json_string escapes
  embedded quotes/backslashes/newlines so the payload is always valid
  JSON. We base64-encode the PDF bytes here (rather than carrying a
  base64 String process variable) because String variables in CIB seven
  cap at 4000 chars in ACT_HI_VARINST.TEXT_.

  The Vehicle Registration Certificate is generated immediately after
  this email and stored as a process Attachment — visible in the
  Documents card. A future PR (payment step) will email it once the
  state fee is paid.

  The pay link carries a payment capability token minted by
  links.payment(execution) (docs/security.md rules 3 and 4), not the
  process instance id.

  The body is the pack document documents/email/vehicle-approval.ftl.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "${(applicantEmail!"")?json_string}", "name": "${fullName?json_string}" } ],
  "subject": "Your vehicle registration is approved — state fee invoice attached",
  "text": "${documents.text("vehicle-approval", execution)?json_string}",
  "attachments": [
    {
      "filename": "${(approvalPdfFilename!"state-fee-invoice.pdf")?json_string}",
      "contentType": "application/pdf",
      "base64": "${pdf.encode(approvalPdfBytes)}"
    }
  ]
}
