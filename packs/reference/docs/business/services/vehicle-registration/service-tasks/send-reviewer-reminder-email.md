# Service task: `send-reviewer-reminder-email`

**Task id:** `send-reviewer-reminder-email`
**BPMN task id:** `Task_SendReminderEmail`
**Display name:** `Send reviewer reminder email`
**Connector:** `http-connector`
**Async-before:** `true`

Reminds the Transport Authority that a registration still waits for review.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/v1/send` |
| Headers | `Content-Type: application/json` |

Downstream: Mailpit (the bus routes `/api/v1/send`).

## Payload (request body)

```
payload-template: reviewer-reminder-email.json.ftl
```

**Document:** `documents/email/vehicle-reviewer-reminder.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
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
```

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Started by the non-interrupting timer `BoundaryEvent_ReviewReminder` on `Task_Review` (`R/PT2M`, every two minutes in the demo) and ends in its own end event.
- It used to be inline JSON in the BPMN, where the applicant's name went in unescaped; it is a template with `?json_string` now.
