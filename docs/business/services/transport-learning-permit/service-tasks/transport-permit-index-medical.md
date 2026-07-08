# Service task: `transport-permit-index-medical`

**BPMN task:** `Task_TransportPermitIndexMedical`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Refreshes the case summary card when the eligibility decision routes the
applicant to a medical fitness assessment — the classic "why is my case
stuck?" state this index exists for. See
[`transport-permit-index-submitted.md`](transport-permit-index-submitted.md)
for the case-card mechanism.

## Placement

Between `Task_TransportHospitalNoticeEmail` and
`Task_TransportHospitalAssessment` (the human assessment task) — the card
flips to "awaiting medical assessment" as soon as the applicant is notified.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-index-medical.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-learning-permit",
  "status": "awaiting-medical",
  "summary": "Driving learner permit application by ${applicantName!\"\"}, civil ID ${civilId!\"\"}, license category ${licenseCategory!\"\"}. Status: on hold — the applicant must attend a medical fitness assessment at the hospital before the application can proceed. The case is waiting for the medical assessment result."
}
```

## Response → output parameters

None — fire-and-forget.

## Why these notes matter

- Same internal-chain / bus-injected-token pattern — no token header in the
  BPMN.
- Placed *before* the human task so the card is fresh during the wait, not
  after it resolves.
