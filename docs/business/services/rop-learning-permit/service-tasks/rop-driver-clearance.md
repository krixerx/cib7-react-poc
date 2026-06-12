# Service task: `rop-driver-clearance`

**BPMN task:** `Task_RopDriverClearance`
**Kind:** http-connector → backend (driver clearance / approved-opticians stand-in)
**Async:** `asyncBefore="true"`

Stand-in for the demo's "Service Link with Other Parties": approved
optical shops (eye test result), the license registry (existing temporary
license), and the restrictions check (violations, circulars). Backed by
the `rop_driver_clearances` table (JPA entity `DriverClearance`).

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| GET | `${apiBaseUrl}/api/public/rop/driver-clearance/${civilId}` | `Accept: application/json` |

Returns 200 for ANY civil ID — unknown IDs come back all-clear with
`eyeTestResult: "pass"`; the seeded demo IDs (`90000001` weak, `90000002`
fail, `90000003` missing, `90000004` valid license, `90000005`
restrictions) trigger the other branches.

## Response → output parameters

Response body: `{ "civilId": "...", "eyeTestResult": "pass|weak|fail|missing",
"hasValidTemporaryLicense": false, "restrictionsCleared": true }`

| Output variable | Type | Expression |
|---|---|---|
| `eyeTestResult` | String | `${!S(response).hasProp('eyeTestResult') ? 'missing' : S(response).prop('eyeTestResult').stringValue()}` |
| `hasValidTemporaryLicense` | Boolean | `${!S(response).hasProp('hasValidTemporaryLicense') ? false : S(response).prop('hasValidTemporaryLicense').boolValue()}` |
| `restrictionsCleared` | Boolean | `${!S(response).hasProp('restrictionsCleared') ? false : S(response).prop('restrictionsCleared').boolValue()}` |

Fallbacks fail safe: a malformed response yields `missing` /
restrictions-not-cleared, which rejects with an actionable reason instead
of approving.

## Why these notes matter

- The eligibility DMN consumes all three values immediately after this
  task.
- The eye test deliberately never comes from the applicant — matching the
  demo's "verify the eye test data electronically" requirement.
