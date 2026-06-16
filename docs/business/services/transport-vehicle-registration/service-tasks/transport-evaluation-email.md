# Service task: `transport-evaluation-email`

**BPMN task:** `Task_TransportEvaluationEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo step 6, the last step of the service path: *"Sending a service level
evaluation request (via email, SMS, and application notification)."*

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-evaluation-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `execution`.

Short body: thanks for using the Integrated Traffic System, please rate
the vehicle registration service (1–5 stars), with the case reference.
There is no real survey backend — the body says the link is illustrative.
Sender "Transport Authority"; subject "How was
your vehicle registration experience?".

## Response

Fire-and-forget. The end event "Vehicle registered" follows immediately.
