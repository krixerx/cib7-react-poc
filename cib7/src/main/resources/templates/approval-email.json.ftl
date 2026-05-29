<#--
  Mailpit /api/v1/send payload for the "Send approval email" service task in
  person-registration.bpmn. All process variables of the executing scope are
  in scope: firstName, lastName, age, objectId, price, applicantEmail.

  The BPMN gateway guarantees applicantEmail is non-null and contains '@'
  before this template runs, but we still null-default to keep the template
  robust if it is ever invoked from a different path. ?json_string escapes
  embedded quotes/backslashes/newlines so the resulting payload is always
  valid JSON.
-->
<#assign fullName = (firstName!"") + " " + (lastName!"")>
<#assign body>Hi ${firstName!""},

Your application has been approved.

Applicant: ${fullName}
Product ID: ${objectId!""}
Price: ${(price!0)?string("0.00")}

Thanks,
CIB7 POC</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To": [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${fullName?json_string}" } ],
  "Subject": "Your application has been approved",
  "Text": "${body?json_string}"
}
