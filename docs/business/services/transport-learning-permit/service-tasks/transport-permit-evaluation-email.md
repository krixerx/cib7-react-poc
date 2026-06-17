# Service task: `transport-permit-evaluation-email`

**BPMN task:** `Task_TransportPermitEvaluationEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo step 6, the final step: *"Send notification to request a service
level evaluation (via email, SMS and app notification)."*

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-evaluation-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `execution`.

Short body: thanks for using the Integrated Traffic System, please rate
the learning-permit service (1–5 stars), with the case reference; the
survey link is illustrative. Sender "Transport Authority — General Traffic
Department"; subject "How was your learning permit experience?".

## Response

Fire-and-forget. The end event "Learning permit issued" follows.
