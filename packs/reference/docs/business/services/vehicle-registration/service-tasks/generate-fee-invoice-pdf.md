# Service task: `generate-fee-invoice-pdf`

**Task id:** `generate-fee-invoice-pdf`
**BPMN task id:** `Task_GeneratePdf`
**Display name:** `Generate state fee invoice`
**Connector:** `http-connector`
**Async-before:** `true`

Renders the state fee invoice for an approved registration as a PDF.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/render` |
| Headers | `Content-Type: application/json` |

Downstream: pdf-renderer, which renders HTML to PDF with Gotenberg.

## Payload (request body)

```
payload-template: approval-pdf.json.ftl
```

**Document:** `documents/pdf/vehicle-fee-invoice.ftlh`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  pdf-renderer /render payload for Task_GeneratePdf in vehicle-registration.bpmn: the vehicle state fee invoice.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/vehicle-fee-invoice.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("vehicle-fee-invoice", execution)?json_string}",
  "filename": "state-fee-invoice-${(objectId!"vehicle")?json_string}.pdf"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "firstName": "Ants",
  "lastName": "Avaldaja",
  "applicantEmail": "ants@example.com",
  "vehicleMake": "Škoda",
  "vehicleModel": "Octavia",
  "objectId": "VIN-1234567",
  "price": 38000,
  "stateFee": 75.0,
  "owner": {
    "name": "Olga Omanik",
    "email": "olga@example.com",
    "partyId": "p1"
  }
}
```

## Example: the quoted fee

```json
{
  "firstName": "Ants",
  "lastName": "Avaldaja",
  "applicantEmail": "ants@example.com",
  "vehicleMake": "Škoda",
  "vehicleModel": "Octavia",
  "objectId": "VIN-1234567",
  "price": 38000,
  "stateFee": 123.5,
  "owner": {
    "name": "Olga Omanik",
    "email": "olga@example.com",
    "partyId": "p1"
  }
}
```

| Path | Expected |
|---|---|
| `/html` | `contains "&euro;123.50"` |

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `approvalPdfBytes` | `byte[]` | `${pdf.decode(S(response).prop('base64').stringValue())}` |
| `approvalPdfFilename` | `String` | `${S(response).prop('filename').stringValue()}` |

## Notes

- The amount is `stateFee`, quoted by [`quote-state-fee`](quote-state-fee.md) just before; the fee rule lives in the pack's `backend/payment/vehicle-registration.yaml`, nowhere else.
- The PDF comes back base64-encoded and is stored as `byte[]` (`pdf.decode`), because a String variable over about 4 kB fails the history flush (docs/cib7.md, large process variables).
