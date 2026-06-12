# Service task: `rop-hospital-notice-email`

**BPMN task:** `Task_RopHospitalNoticeEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo: *"If the result is (weak vision), the system sends a notification to
the service applicant to go to the police hospital (to extract a medical
report stating the eligibility for the license)."*

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/rop-hospital-notice-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `licenseCategory`.

Body: the approved optician reported weak vision; please visit the Police
Hospital for a medical examination; the result will be sent to the
Integrated Traffic System automatically and the application continues
from there. Sender "Royal Oman Police — General Traffic Department";
subject "Learning permit — medical examination required at the Police
Hospital".

## Response

Fire-and-forget. The flow then opens the
`Task_RopHospitalAssessment` user task (the hospital-side stand-in).
