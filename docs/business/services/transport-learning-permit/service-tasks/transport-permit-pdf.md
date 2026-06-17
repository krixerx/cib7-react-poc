# Service task: `transport-permit-pdf`

**BPMN task:** `Task_TransportPermitPdf`
**Kind:** http-connector → pdf-renderer (Gotenberg)
**Async:** `asyncBefore="true"`

Generates the **electronic learning license** — the demo's result
artifact (*"Send notification of receipt of electronic driving license
and payment receipt"*). One PDF carries both: the license card layout and
a payment-receipt block.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/render` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-pdf.json.ftl` —
`{ "html": "...", "filename": "..." }`. Variables in scope:
`applicantName`, `civilId`, `age`, `licenseCategory`, `permitNumber`,
`permitValidUntil`, `execution`.

HTML document: Transport Authority letterhead (text only),
title "Driving Learning License", license block (permit number —
prominent —, holder name, civil number, license category, valid until),
and a "Payment receipt" block (service fee 6.000 EUR, paid, case
reference `execution.processInstanceId`). Filename:
`transport-learning-permit.pdf`.

## Response → output parameters

| Output variable | Type | Expression |
|---|---|---|
| `permitPdfBytes` | byte[] | `${pdf.decode(S(response).prop('base64').stringValue())}` — stored bytes to spill to ACT_GE_BYTEARRAY |
| `permitPdfFilename` | String | `${S(response).prop('filename').stringValue()}` |

## Why these notes matter

- Same byte[]-not-String rule as every generated PDF in this repo.
