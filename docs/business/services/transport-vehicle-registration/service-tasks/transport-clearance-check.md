# Service task: `transport-clearance-check`

**BPMN task:** `Task_TransportClearanceCheck`
**Kind:** http-connector → backend (clearance registry stand-in)
**Async:** `asyncBefore="true"` (runs on the job executor right after the
application form is submitted)

Stand-in for three external linkages from the demo document's "Service
Link with Other Parties": technical inspection systems (inspection report
data), insurance companies (vehicle insurance data), and the restrictions
check (payment of fines, circulars, expired vehicles).

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| GET | `${busBaseUrl}/api/public/transport/vehicle-clearance/${vin}` | `Accept: application/json` |

`${busBaseUrl}` resolves from `BusConfiguration.java`. The endpoint
returns 200 for ANY vin — unknown VINs come back all-clear, seeded demo
VINs (`DEMOFAILINSP01`, `DEMONOINSURE02`, `DEMOFINESDUE03`)
trigger the rejection branches. Backed by the `transport_vehicle_clearances`
table in the backend (JPA entity `VehicleClearance`).

## Response → output parameters

Response body: `{ "vin": "...", "inspectionPassed": true, "insured": true,
"restrictionsCleared": true }`

| Output variable | Type | Expression |
|---|---|---|
| `inspectionPassed` | Boolean | `${!S(response).hasProp('inspectionPassed') ? false : S(response).prop('inspectionPassed').boolValue()}` |
| `insured` | Boolean | `${!S(response).hasProp('insured') ? false : S(response).prop('insured').boolValue()}` |
| `restrictionsCleared` | Boolean | `${!S(response).hasProp('restrictionsCleared') ? false : S(response).prop('restrictionsCleared').boolValue()}` |

Missing properties degrade to `false` (fail-safe: a malformed response
rejects rather than approves).

## Why these notes matter

- The eligibility DMN consumes all three booleans immediately after this
  task; the fail-safe defaults mean a registry outage surfaces to the
  applicant as a re-submittable rejection, not an approval.
- On the send-back loop the task re-runs, so a vehicle that was inspected
  or insured between rounds passes on resubmit — matching the demo's
  re-validation semantics.
