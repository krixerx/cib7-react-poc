# Service task: `rop-vehicle-sendback-email`

**BPMN task:** `Task_RopVehicleSendbackEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

The demo's "Return to request an inquiry about the request" outcome — the
officer sends the application back with notes ("The employee can send the
request notes to the service applicant in case of missing attachments").
The flow then loops to `Task_RopVehicleApplication`.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/rop-vehicle-sendback-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `vin`,
`sendBackReason`.

Body: greeting, "your vehicle registration application needs corrections",
the officer's notes (`sendBackReason`), and instructions to reopen the
case in the portal, fix the data, and resubmit. Sender "Royal Oman Police —
General Traffic Department"; subject "Your vehicle registration
application needs corrections".

## Response

Fire-and-forget.

## Why these notes matter

- The applicant form shows the same `sendBackReason` as a banner when the
  task reopens — email and in-portal banner carry identical text by
  construction (both read the same process variable).
- `?json_string` on every interpolated string.
