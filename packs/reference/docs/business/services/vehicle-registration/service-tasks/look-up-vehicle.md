# Service task: `look-up-vehicle`

**Task id:** `look-up-vehicle`
**BPMN task id:** `Task_GetPrice`
**Display name:** `Look up vehicle in registry`
**Connector:** `http-connector`
**Async-before:** `true`

Reads the chosen vehicle from the vehicle registry and stores its value and details on the case for the auto-approval decision and the documents.

## Request

| Field | Value |
|---|---|
| Method | `GET` |
| URL | `${busBaseUrl}/api/public/registry/vehicles/${objectId}` |
| Headers | `Accept: application/json` |

Downstream: backend registry module, public `vehicles` registry (no personal data).

## Payload (request body)

None: a `GET` carries no body.

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `price` | number | `${!S(response).hasProp('value') ? 9999 : S(response).prop('value').numberValue()}` |
| `vehicleMake` | `String` | `${!S(response).hasProp('make') ? '' : S(response).prop('make').stringValue()}` |
| `vehicleModel` | `String` | `${!S(response).hasProp('model') ? '' : S(response).prop('model').stringValue()}` |
| `vehicleYear` | number | `${!S(response).hasProp('year') ? 0 : S(response).prop('year').numberValue()}` |
| `vehicleFuelType` | `String` | `${!S(response).hasProp('fuelType') ? '' : S(response).prop('fuelType').stringValue()}` |
| `vehicleAgeYears` | number | `${!S(response).hasProp('ageYears') ? 0 : S(response).prop('ageYears').numberValue()}` |

## Notes

- The registry is declared in [`data/vehicles.md`](../data/vehicles.md); this call uses its public read endpoint, which carries no personal data.
- `${objectId}` goes into the URL path unencoded. That is safe only because the engine accepts nothing but a VIN there: the form schema holds `objectId` to the shared `vin` rule (17 characters, `A-Z0-9`, docs/security.md rule 2). Keep that rule if this changes.
- Every mapping has a default, so a response without a field still yields a value; `price` defaults to 9999, which the decision neither auto-approves nor treats as luxury, so the case goes to review.
