# Service task: `transport-vehicle-index-sendback`

**BPMN task:** `Task_TransportVehicleIndexSendback`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Refreshes the case summary card when the officer returns the application for
corrections. See
[`transport-vehicle-index-submitted.md`](transport-vehicle-index-submitted.md)
for the case-card mechanism; this task re-posts the card with the same
`processInstanceId`, replacing the previous status.

## Placement

Between `Task_TransportVehicleSendbackEmail` and the sequence flow that loops
back to `Task_TransportVehicleApplication` (officer default / send-back
branch).

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-vehicle-index-sendback.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-vehicle-registration",
  "status": "sent-back",
  "summary": "Transport vehicle registration application by ${applicantName!\"\"} for vehicle VIN ${vin!\"\"}. Status: sent back — returned to the applicant for corrections by the reviewing officer. Reason: ${sendBackReason!\"not specified\"}. Waiting for the applicant to amend and resubmit the application."
}
```

## Response → output parameters

None — fire-and-forget.

## Why these notes matter

- Same internal-chain / bus-injected-token pattern as the other
  `/api/documents/**` tasks — no token header in the BPMN.
- `sendBackReason` comes from the officer review form; defensively defaulted
  because the card must render even if the form ever makes it optional.
