# Service task: `send-business-sendback-email`

**BPMN task:** `Task_SendBackEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at `cib7/src/main/resources/templates/business-sendback-email.json.ftl`.
Variables in scope: `companyName`, `applicantFirstName`,
`applicantLastName`, `sendBackReason`.

The template renders the Mailpit `/api/v1/send` JSON payload. The `Text`
body includes the applicant's name, the company name they tried to
register, the civil-servant's send-back reason, and a link back to the
React portal so the applicant can resubmit.

## Why these notes matter

- `sendBackReason` is required at this point — the civil-servant form
  enforces non-empty on "Send back..." submit, and the gateway only
  routes here when `decision == "sendback"`. The applicant's next-iteration
  `business-details` form reads the same variable to render the banner;
  the loop is closed.
- The template escapes all string values with `?json_string`.
