# Service task: `transport-permit-index-submitted`

**BPMN task:** `Task_TransportPermitIndexSubmitted`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Pushes a fresh *case summary card* into the backend's case-card store
when the permit application is submitted. Cards make cases findable by
meaning through the `search_cases` MCP tool; each milestone task re-posts the
card with the same `processInstanceId`, so the index always holds exactly one
card per case reflecting its latest status.

## Placement

Between `Task_TransportPermitApplication` (application user task) and
`Task_TransportDriverClearance` — first system step after submission.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-index-submitted.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-learning-permit",
  "status": "submitted",
  "summary": "Driving learner permit application submitted by ${applicantName!\"\"}, civil ID ${civilId!\"\"}, license category ${licenseCategory!\"\"}. Status: submitted — awaiting driver clearance checks (eye test, existing licenses, restrictions) and eligibility decision."
}
```

Every interpolation is `?json_string`-escaped and defensively defaulted
(`!""`) — MCP-started instances may omit form-internal variables.

## Response → output parameters

None — fire-and-forget. Indexing is best-effort; the process never branches
on it.

## Why these notes matter

- Goes through the **internal** chain (`X-Internal-Token`) — the call crosses
  the integration bus (`esb`), which injects the token on the
  `/api/documents` route. No token header in the BPMN —
  `/service-builder` must not re-add it.
- The `summary` is written for an LLM reader: natural language,
  information-dense, phrased the way an applicant would ask about their case.
