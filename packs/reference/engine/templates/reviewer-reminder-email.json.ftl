<#--
  mail-send payload (Patchbay integration, v1) for Task_SendReminderEmail in
  vehicle-registration.bpmn: the reminder to the Transport Authority while a
  case waits for review. The body is the pack document
  documents/email/vehicle-reviewer-reminder.ftl.
-->
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "civil-servant@cib7-poc.local", "name": "Transport Authority officer" } ],
  "subject": "Reminder: vehicle registration awaiting review",
  "text": "${documents.text("vehicle-reviewer-reminder", execution)?json_string}"
}
