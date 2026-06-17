# Service task: `transport-vehicle-rejection-email`

**BPMN task:** `Task_TransportVehicleRejectionEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

The demo's rejection notice: *"a rejection notice is sent to the service
applicant with the reasons for the rejection."* This single task serves
BOTH rejection paths — system rejection (DMN conditions not met) and
traffic-officer rejection — so it has two incoming sequence flows.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-vehicle-rejection-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `vin`,
`vehicleCategory`, `rejectionReason` (may be empty/unset on the system
path), `eligibilityDecision`.

Reason resolution inside the template:

```ftl
<#assign reason = (rejectionReason!"")?has_content?then(rejectionReason, eligibilityDecision!"Conditions not met")>
```

— the officer's explicit reason wins; otherwise the DMN's rejection
sentence (which is human-readable by design) is the reason. Body: greeting
by `applicantName`, the vehicle (VIN + category), the reason, and a note
that a corrected application can be submitted as a new case. Sender:
`process@cib7-poc.local`, name "Transport Authority — General Traffic
Department". Subject: "Your vehicle registration application was
rejected".

## Response

Fire-and-forget — Mailpit's `{ID, Total}` response is not captured.

## Why these notes matter

- The officer review form always completes `rejectionReason` (empty string
  on non-reject outcomes), so `?has_content` is the correct guard — a
  plain `!` default would mask an empty officer reason.
- All strings rendered into JSON are escaped with `?json_string`.
