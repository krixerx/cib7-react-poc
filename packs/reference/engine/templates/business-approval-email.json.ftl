<#--
  Mailpit /api/v1/send payload for the businessRegistration approval email.
  Variables in scope: companyName, shareCapital, applicantFirstName,
  applicantLastName, applicantEmail, boardMembers (Spin Json list),
  autoDecision, decision, feeInvoicePdfBytes (raw PDF bytes, written by
  the preceding Task_GenerateFeeInvoicePdf) and feeInvoicePdfFilename,
  plus the execution properties.

  All string fields escaped with ?json_string. boardMembers iterated via
  Spin's elements() iterator and stringified for the human-readable body.

  Synthesises a Business Register code from the process instance id so
  the email reads like a real Äriregister confirmation. Demo only — the
  real code is allocated by the registry.

  We base64-encode the PDF bytes here (rather than carrying a base64
  String process variable) because String variables in CIB seven cap at
  4000 chars in ACT_HI_VARINST.TEXT_. Same byte[]→base64 trip as
  the vehicle service's approval-email.json.ftl.

  Recipient is the applicant's own email — Gateway_SendApprovalEmail
  upstream guarantees it is non-null and contains '@' before this
  template runs.

  The pay link carries a payment capability token minted by
  links.payment(execution) (docs/security.md rules 3 and 4), not the
  process instance id.

  The body is the pack document documents/email/business-approval.ftl.
-->
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
{
  "From":    { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To":      [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "${("Estonian OÜ registered: " + (companyName!""))?json_string}",
  "Text":    "${documents.text("business-approval", execution)?json_string}",
  "Attachments": [
    {
      "Filename": "${(feeInvoicePdfFilename!"state-fee-invoice.pdf")?json_string}",
      "ContentType": "application/pdf",
      "Content": "${pdf.encode(feeInvoicePdfBytes)}"
    }
  ]
}
