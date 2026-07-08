# Service task: `transport-vehicle-index-submitted`

**BPMN task:** `Task_TransportVehicleIndexSubmitted`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Pushes a fresh *case summary card* into the backend's case-card store
the moment the application is submitted. Cards make cases findable by meaning
through the `search_cases` MCP tool ("which of my cases is waiting for
review?"); each milestone task in this process re-posts the card with the
same `processInstanceId`, so the index always holds exactly one card per case
reflecting its latest status.

## Placement

Between `Task_TransportVehicleApplication` (application user task) and
`Task_TransportClearanceCheck` — first system step after submission.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-vehicle-index-submitted.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-vehicle-registration",
  "status": "submitted",
  "summary": "Transport vehicle registration application submitted by ${applicantName!\"\"} for vehicle VIN ${vin!\"\"}, category ${vehicleCategory!\"\"}, registration type ${registrationType!\"\"}, plate option ${plateOption!\"\"}. Status: submitted — awaiting automated clearance checks (inspection, insurance, restrictions) and officer review."
}
```

Every interpolation is `?json_string`-escaped and defensively defaulted
(`!""`) — MCP-started instances may omit form-internal variables.

## Response → output parameters

None — fire-and-forget. The backend replies `{"indexed": true}`; indexing is
best-effort by design and the process never branches on it.

## Why these notes matter

- Goes through the **internal** chain (`X-Internal-Token`) — the call crosses
  the integration bus (`esb`), which injects the token on the
  `/api/documents` route. The spec table above therefore lists no token
  header — `/service-builder` must not re-add it.
- The `summary` is written for an LLM reader, not a UI: natural
  language, information-dense, includes the service name, applicant, key
  identifiers, and a status phrase an AI client's user might search for.
