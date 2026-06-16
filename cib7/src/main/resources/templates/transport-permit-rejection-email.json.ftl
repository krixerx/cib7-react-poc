<#--
  Mailpit /api/v1/send payload for Task_TransportPermitRejectionEmail in
  transport-learning-permit.bpmn. Serves BOTH rejection paths:
    - DMN terms-of-service rejection: permitDecision text IS the reason
    - Police Hospital negative assessment: rejectionReason wins
  Variables in scope: applicantName, applicantEmail, licenseCategory,
  rejectionReason (may be unset/empty), permitDecision.
-->
<#assign reason = ((rejectionReason!"")?has_content)?then(rejectionReason!"", permitDecision!"Conditions not met")>
<#assign body>Dear ${applicantName!""},

Your driving learning permit application (category ${licenseCategory!""})
has been rejected.

Reason:
${reason}

Once the condition above is resolved, you are welcome to submit a new
application through the portal.

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Your learning permit application was rejected",
  "Text": "${body?json_string}"
}
