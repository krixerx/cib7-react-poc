# Service task: `quote-state-fee`

**Task id:** `quote-state-fee`
**BPMN task id:** `Task_QuoteFee`
**Display name:** `Quote state fee`
**Connector:** `http-connector`
**Async-before:** `true`

Asks the backend for the case's state fee, so the invoice prints exactly the amount the checkout will charge.

## Request

| Field | Value |
|---|---|
| Method | `GET` |
| URL | `${busBaseUrl}/api/internal/payments/quote/${execution.processInstanceId}` |
| Headers | `Accept: application/json` |

Downstream: backend payment module, which computes the fee from the pack's `backend/payment/` rule (the bus adds `X-Internal-Token`).

## Payload (request body)

None: a `GET` carries no body.

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `stateFee` | number | `${S(response).prop('amount').numberValue()}` |
| `stateFeeCurrency` | `String` | `${S(response).prop('currency').stringValue()}` |

## Notes

- The backend is the only place a fee is computed: from the pack's `backend/payment/vehicle-registration.yaml` (EUR 25, 75 or 150 by `price`) for the checkout, the provider callback and this quote alike (docs/security.md rule 4).
- A process without a fee rule gets a 404, which becomes an incident: a payable service needs its rule.
