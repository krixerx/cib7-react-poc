# Service task: `rop-allocate-plate`

**BPMN task:** `Task_RopAllocatePlate`
**Kind:** http-connector → backend (plate registry)
**Async:** `asyncBefore="true"` (first task after the payment is
correlated)

Demo step 4: *"Receiving vehicle plates"* / *"Vehicles are registered with
new (random) number plates or with previously reserved number plates."*
The backend persists the registration record (table
`rop_plate_registrations`, JPA entity `PlateRegistration`) and returns the
allocated plate — random Omani-style `NNNNN AB` for `plateOption=random`,
or the validated `reservedPlateNumber`.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${apiBaseUrl}/api/public/rop/plates/allocate` | `Content-Type: application/json`, `Accept: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/rop-allocate-plate.json.ftl`.
Variables in scope: `execution`, `vin`, `applicantName`,
`vehicleCategory`, `plateOption`, `reservedPlateNumber`.

```json
{
  "processInstanceId": "...",
  "vin": "...",
  "ownerName": "...",
  "vehicleCategory": "...",
  "plateOption": "random | reserved",
  "reservedPlateNumber": ""
}
```

All strings `?json_string`-escaped, nullables defaulted with `!""`.

## Response → output parameters

Response body: `{ "plateNumber": "12345 AB", "registrationId": 1,
"issuedAt": "..." }`

| Output variable | Type | Expression |
|---|---|---|
| `plateNumber` | String | `${S(response).prop('plateNumber').stringValue()}` |

No defensive fallback here on purpose: if the plate registry fails, the
engine raises an incident on this task (visible in PartB's Incidents page
and Cockpit) rather than issuing a certificate without a plate.

## Why these notes matter

- This is the service's system-of-record write — the demo requirement
  "integration to database" lands here: one row per completed
  registration, queryable independently of engine history.
- Runs strictly AFTER payment correlation, so no plate is ever allocated
  for an unpaid case.
