# Service task: `transport-issue-permit`

**BPMN task:** `Task_TransportIssuePermit`
**Kind:** http-connector → backend (learning-permit registry)
**Async:** `asyncBefore="true"` (first task after the payment is
correlated)

The system-of-record write: the backend persists the issued permit (table
`transport_learning_permits`, JPA entity `LearningPermitRecord`) and returns
the permit number and validity.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/public/transport/learning-permits/issue` | `Content-Type: application/json`, `Accept: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-issue-permit.json.ftl`:

```json
{
  "processInstanceId": "...",
  "civilId": "...",
  "applicantName": "...",
  "licenseCategory": "..."
}
```

All strings `?json_string`-escaped.

## Response → output parameters

Response body: `{ "permitNumber": "LP-2026-000123", "validUntil":
"2027-06-12", "issuedAt": "..." }`

| Output variable | Type | Expression |
|---|---|---|
| `permitNumber` | String | `${S(response).prop('permitNumber').stringValue()}` |
| `permitValidUntil` | String | `${S(response).prop('validUntil').stringValue()}` |

No defensive fallback — a registry failure must raise an incident, not
issue a permit without a number.

## Why these notes matter

- Runs strictly AFTER payment correlation — no permit exists for an
  unpaid case.
- Validity is one year from issue, computed server-side.
