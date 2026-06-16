# Service task: `transport-permit-rejection-email`

**BPMN task:** `Task_TransportPermitRejectionEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

The demo's rejection notice. Serves BOTH rejection paths — DMN terms not
met and Police Hospital negative assessment — so it has two incoming
sequence flows.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-rejection-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `licenseCategory`,
`rejectionReason` (set by the hospital form on negative; may be
unset/empty on the DMN path), `permitDecision`.

Reason resolution:

```ftl
<#assign reason = (rejectionReason!"")?has_content?then(rejectionReason, permitDecision!"Conditions not met")>
```

Body: greeting, "your learning permit application was rejected", the
reason, and a note that a new application can be submitted once the
condition is resolved. Sender "Transport Authority — General Traffic
Department"; subject "Your learning permit application was rejected".

## Response

Fire-and-forget. The end event "Application rejected" follows.

## Why these notes matter

- On the DMN path `permitDecision` IS the human-readable reason (the
  decision table outputs sentences); on the hospital path
  `rejectionReason` carries the medical wording. `?has_content` (not a
  bare `!` default) distinguishes empty-string from set.
