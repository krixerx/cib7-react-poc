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
  personReg's approval-email.json.ftl.

  Recipient is the applicant's own email — Gateway_SendApprovalEmail
  upstream guarantees it is non-null and contains '@' before this
  template runs.
-->
<#assign caseRef = execution.processInstanceId>
<#assign regCode = "1" + caseRef?replace("-", "")?substring(0, 7)>
<#assign fullName = (applicantFirstName!"") + " " + (applicantLastName!"")>
<#assign members>
<#list boardMembers.elements() as m>
- ${(m.prop("firstName").stringValue())!""} ${(m.prop("lastName").stringValue())!""} (isikukood ${(m.prop("personalCode").stringValue())!""})
</#list>
</#assign>
<#assign residencyRaw = (applicantResidency!"citizen")>
<#if residencyRaw == "e-resident">
  <#assign residencyLabel = "e-resident">
<#elseif residencyRaw == "foreign">
  <#assign residencyLabel = "foreign founder">
<#else>
  <#assign residencyLabel = "Estonian citizen">
</#if>
<#assign body>Tere ${(applicantFirstName!"")} ${(applicantLastName!"")},

Your Estonian OÜ has been approved by the Business Register and the
state fee invoice is attached. Pay the listed amount and your B-card
extract will be issued.

Business Register code:  ${regCode}
Company:                 ${(companyName!"")}
Share capital:           ${(shareCapital!0.0)?string("0.00")} EUR
Founder residency:       ${residencyLabel}
Board members:
${members}
Decision made by:        ${((autoDecision!"")=="approve")?then("automated decision table (DMN)", "manual review by the Business Register")}

Pay the state fee here:
${frontendBaseUrl}/pay/${execution.processInstanceId}

Once payment is received, the B-card extract will be available in the My
processes page in the SPA.

Tervitustega,
Äriregister POC
</#assign>
{
  "From":    { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To":      [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "${("Estonian OÜ registered: " + (companyName!""))?json_string}",
  "Text":    "${body?json_string}",
  "Attachments": [
    {
      "Filename": "${(feeInvoicePdfFilename!"state-fee-invoice.pdf")?json_string}",
      "ContentType": "application/pdf",
      "Content": "${pdf.encode(feeInvoicePdfBytes)}"
    }
  ]
}
