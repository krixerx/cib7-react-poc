# Service task: `transport-vehicle-index-rejected`

**BPMN task:** `Task_TransportVehicleIndexRejected`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Refreshes the case summary card when the application is rejected — by either
path: the automated eligibility DMN (`Gateway_ConditionsMet`) or the officer
decision. Both reject flows already converge on
`Task_TransportVehicleRejectionEmail`, so one index task covers both. See
[`transport-vehicle-index-submitted.md`](transport-vehicle-index-submitted.md)
for the case-card mechanism.

## Placement

Between `Task_TransportVehicleRejectionEmail` and `EndEvent_Rejected`.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-vehicle-index-rejected.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-vehicle-registration",
  "status": "rejected",
  "summary": "Transport vehicle registration application by ${applicantName!\"\"} for vehicle VIN ${vin!\"\"}. Status: rejected. Reason: ${rejectionReason!\"automated eligibility checks failed (vehicle inspection, insurance, or outstanding restrictions)\"}. The case is closed."
}
```

## Response → output parameters

None — fire-and-forget.

## Why these notes matter

- Same internal-chain / bus-injected-token pattern — no token header in the
  BPMN.
- `rejectionReason` only exists on the *officer* reject path; on the system
  (DMN) path the FreeMarker default supplies a generic clearance-failure
  phrase — do not use a bare `${rejectionReason}` (JUEL/FTL both throw on
  missing vars).
