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
| URL | `${patchbayBaseUrl}/api/v1/integrations/mail-send` |
| Headers | `Content-Type: application/json` |

Downstream: Patchbay integration `mail-send` (v1), which hands the mail to the mail system (Mailpit here).

## Payload (request body)

```
payload-template: reviewer-reminder-email.json.ftl
```

**Document:** `documents/email/vehicle-reviewer-reminder.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
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
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "firstName": "Ants",
  "lastName": "Avaldaja"
}
```

| Path | Expected |
|---|---|
| `/from/email` | `"process@cib7-poc.local"` |
| `/to/0/email` | `"civil-servant@cib7-poc.local"` |

## Example: hostile names

```json
{
  "firstName": "An\"ts\\",
  "lastName": "Ava\nldaja\t<b>"
}
```

| Path | Expected |
|---|---|
| `/text` | `contains "An\"ts\\ Ava\nldaja\t<b>"` |

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Started by the non-interrupting timer `BoundaryEvent_ReviewReminder` on `Task_Review` (`R/PT2M`, every two minutes in the demo) and ends in its own end event.
- It used to be inline JSON in the BPMN, where the applicant's name went in unescaped; it is a template with `?json_string` now.
