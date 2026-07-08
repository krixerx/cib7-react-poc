# Service task: `transport-vehicle-index-registered`

**BPMN task:** `Task_TransportVehicleIndexRegistered`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Refreshes the case summary card when registration completes: plate allocated,
certificate generated and stored. This is the terminal card for the happy
path. See
[`transport-vehicle-index-submitted.md`](transport-vehicle-index-submitted.md)
for the case-card mechanism.

## Placement

Between `Task_TransportStoreCertificate` and
`Task_TransportCertificateEmail` — after the certificate exists (so the card
can truthfully say it was issued), before the notification emails.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-vehicle-index-registered.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-vehicle-registration",
  "status": "registered",
  "summary": "Transport vehicle registration by ${applicantName!\"\"} for vehicle VIN ${vin!\"\"}, category ${vehicleCategory!\"\"}. Status: completed — vehicle registered, plate number ${plateNumber!\"\"} allocated, registration certificate issued and available in the case documents. The case is closed successfully."
}
```

## Response → output parameters

None — fire-and-forget.

## Why these notes matter

- Same internal-chain / bus-injected-token pattern — no token header in the
  BPMN.
- Deliberately *not* placed after the evaluation email: the milestone is
  "certificate stored", and putting it earlier keeps the card correct even if
  a later email task incidents.
- `plateNumber` is the output of `Task_TransportAllocatePlate`, upstream on
  this path, so it is always present here; still defaulted per convention.
