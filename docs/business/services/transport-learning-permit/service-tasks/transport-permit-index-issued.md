# Service task: `transport-permit-index-issued`

**BPMN task:** `Task_TransportPermitIndexIssued`
**Kind:** http-connector → backend documents (internal)
**Async:** `asyncBefore="true"`

Refreshes the case summary card when the learning permit is issued and the
electronic license PDF is stored — the terminal card for the happy path. See
[`transport-permit-index-submitted.md`](transport-permit-index-submitted.md)
for the case-card mechanism.

## Placement

Between `Task_TransportStorePermit` and `Task_TransportPermitEmail` — after
the license document exists, before the notification emails.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/documents/index-case` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-index-issued.json.ftl`:

```json
{
  "processInstanceId": "${execution.processInstanceId}",
  "service": "transport-learning-permit",
  "status": "issued",
  "summary": "Driving learner permit for ${applicantName!\"\"}, civil ID ${civilId!\"\"}, license category ${licenseCategory!\"\"}. Status: completed — learning permit ${permitNumber!\"\"} issued, valid until ${permitValidUntil!\"\"}, electronic license stored in the case documents. The case is closed successfully."
}
```

## Response → output parameters

None — fire-and-forget.

## Why these notes matter

- Same internal-chain / bus-injected-token pattern — no token header in the
  BPMN.
- `permitNumber` / `permitValidUntil` are outputs of
  `Task_TransportIssuePermit`, upstream on this path — always present, still
  defaulted per convention.
