# Service task: `transport-permit-index-rejected`

**BPMN task:** `Task_TransportPermitIndexRejected`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Refreshes the case summary card when the application is rejected — by either
path: the eligibility DMN (`Gateway_PermitConditions` default) or a negative
medical assessment (`Gateway_MedicalResult`). Both reject flows already
converge on `Task_TransportPermitRejectionEmail`, so one index task covers
both. See
[`transport-permit-index-submitted.md`](transport-permit-index-submitted.md)
for the case-card mechanism.

## Placement

Between `Task_TransportPermitRejectionEmail` and `EndEvent_Rejected`.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-index-rejected.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-learning-permit",
  "status": "rejected",
  "summary": "Driving learner permit application by ${applicantName!\"\"}, civil ID ${civilId!\"\"}. Status: rejected. Reason: ${rejectionReason!\"eligibility requirements not met (age, eye test, existing license status, or medical assessment)\"}. The case is closed."
}
```

## Response → output parameters

None — fire-and-forget.

## Why these notes matter

- Same internal-chain / bus-injected-token pattern — no token header in the
  BPMN.
- `rejectionReason` may be absent on the DMN reject path — the FreeMarker
  default supplies a generic eligibility phrase; never emit a bare
  `${rejectionReason}`.
