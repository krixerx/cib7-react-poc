# Service task: `transport-hospital-notice-email`

**BPMN task:** `Task_TransportHospitalNoticeEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo: *"If the result is (weak vision), the system sends a notification to
the service applicant to go to the police hospital (to extract a medical
report stating the eligibility for the license)."*

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-hospital-notice-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `licenseCategory`.

Body: the approved optician reported weak vision; please visit the Police
Hospital for a medical examination; the result will be sent to the
Integrated Traffic System automatically and the application continues
from there. Sender "Transport Authority";
subject "Learning permit — medical examination required at the Police
Hospital".

## Response

Fire-and-forget. The flow then opens the
`Task_TransportHospitalAssessment` user task (the hospital-side stand-in).
