# Service task: `transport-vehicle-payment-email`

**BPMN task:** `Task_TransportVehiclePaymentEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo step 2→3 handoff: *"If all procedures are completed, a notice is sent
to the service applicant to pay the fees."* The case then parks on
`Task_TransportWaitFeePayment` until the fee is confirmed on the public payment
page.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-vehicle-payment-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `vehicleCategory`,
`vin`, `registrationFee`, `execution` (for `processInstanceId`),
`frontendBaseUrl`.

Body: the approved-application summary, the amount due —
`${registrationFee} EUR` (use the defensive numeric-coercion pattern from
the FreeMarker-numeric-vars memory before `?string("0.000")`; EUR has 3
decimal places) — and the payment link:

```ftl
<#assign payUrl = frontendBaseUrl + "/pay/" + execution.processInstanceId>
```

Sender "Transport Authority"; subject
"Vehicle registration approved — pay the registration fee".

## Response

Fire-and-forget.

## Why these notes matter

- The shared `PaymentController` resolves this definition key
  (`transportVehicleRegistration`) to the `registrationFee` variable and renders
  the pay page in EUR — the email's amount and the pay page's amount come
  from the same DMN-written variable.
- `registrationFee` is written by a DMN and can surface through JUEL as a
  locale-formatted String — coerce defensively in the template.
