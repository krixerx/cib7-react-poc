# Service task: `quote-business-state-fee`

**Task id:** `quote-business-state-fee`
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

- The fee rule is the pack's `backend/payment/business-registration.yaml` (EUR 265 flat); the backend computes it for the checkout, the provider callback and this quote alike (docs/security.md rule 4).
