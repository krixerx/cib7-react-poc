<#--
  Mailpit /api/v1/send payload for Task_SendReminderEmail in
  vehicle-registration.bpmn: the reminder to the Transport Authority while a
  case waits for review. The body is the pack document
  documents/email/vehicle-reviewer-reminder.ftl.
-->
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "civil-servant@cib7-poc.local", "Name": "Transport Authority officer" } ],
  "Subject": "Reminder: vehicle registration awaiting review",
  "Text": "${documents.text("vehicle-reviewer-reminder", execution)?json_string}"
}
