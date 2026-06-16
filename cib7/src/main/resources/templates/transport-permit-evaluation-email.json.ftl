<#--
  Mailpit /api/v1/send payload for Task_TransportPermitEvaluationEmail in
  transport-learning-permit.bpmn. Demo step 6 — service level evaluation
  request. Variables in scope: applicantName, applicantEmail, execution.
-->
<#assign body>Dear ${applicantName!""},

Thank you for using the Integrated Traffic System.

We would appreciate it if you rated the learning permit service
(1–5 stars). The survey link below is illustrative in this POC:

https://its.transport.example/survey/${execution.processInstanceId}

Case reference: ${execution.processInstanceId}

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "How was your learning permit experience?",
  "Text": "${body?json_string}"
}
