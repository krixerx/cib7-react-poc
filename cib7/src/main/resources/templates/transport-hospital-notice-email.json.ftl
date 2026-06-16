<#--
  Mailpit /api/v1/send payload for Task_TransportHospitalNoticeEmail in
  transport-learning-permit.bpmn. The approved optician reported weak vision —
  demo: "the system sends a notification to the service applicant to go to
  the police hospital". Variables in scope: applicantName, applicantEmail,
  licenseCategory.
-->
<#assign body>Dear ${applicantName!""},

The eye test result reported by the approved optician shows weak vision.
Before your learning permit application (category ${licenseCategory!""})
can continue, please visit the Police Hospital for a medical examination
to confirm your eligibility for a driving license.

The hospital sends the result to the Integrated Traffic System
automatically — your application continues from there. If the result is
positive you will receive a notification to pay the service fee.

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Learning permit — medical examination required at the Police Hospital",
  "Text": "${body?json_string}"
}
