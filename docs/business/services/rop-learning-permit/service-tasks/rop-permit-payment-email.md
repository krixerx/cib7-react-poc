# Service task: `rop-permit-payment-email`

**BPMN task:** `Task_RopPermitPaymentEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo: *"If all conditions are met, a notification is sent to the service
applicant to pay the fees"* — also reached from the hospital branch on a
positive assessment (*"The result is positive. The service applicant
receives a notification to pay the fees."*). Two incoming sequence flows.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/rop-permit-payment-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `licenseCategory`,
`execution`, `frontendBaseUrl`.

Body: the application is approved pending payment; the **service fee is
6.000 OMR** (flat — hard-coded in the template AND in
`PaymentController`, which renders the same amount on the pay page for
this definition key); the payment link:

```ftl
<#assign payUrl = frontendBaseUrl + "/pay/" + execution.processInstanceId>
```

Sender "Royal Oman Police — General Traffic Department"; subject
"Learning permit approved — pay the 6 OMR service fee".

## Response

Fire-and-forget. The case parks on `Task_RopWaitPermitPayment`
(message `PaymentReceived`).
