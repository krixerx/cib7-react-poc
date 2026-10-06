# Service task: `generate-business-fee-invoice-pdf`

**Task id:** `generate-business-fee-invoice-pdf`
**BPMN task id:** `Task_GenerateFeeInvoicePdf`
**Display name:** `Generate state fee invoice`
**Connector:** `http-connector`
**Async-before:** `true`

Renders the state fee invoice for an approved registration.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/render` |
| Headers | `Content-Type: application/json` |

Downstream: pdf-renderer, which renders HTML to PDF with Gotenberg.

## Payload (request body)

```
payload-template: business-fee-invoice-pdf.json.ftl
```

**Document:** `documents/pdf/business-fee-invoice.ftlh`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  pdf-renderer /render payload for the state fee invoice task in business-registration.bpmn: the OÜ state fee invoice.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/business-fee-invoice.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("business-fee-invoice", execution)?json_string}",
  "filename": "state-fee-invoice-${(companyName!"OU")?json_string}.pdf"
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `feeInvoicePdfBytes` | `byte[]` | `${pdf.decode(S(response).prop('base64').stringValue())}` |
| `feeInvoicePdfFilename` | `String` | `${S(response).prop('filename').stringValue()}` |

## Notes

- The amount is `stateFee`, quoted by [`quote-business-state-fee`](quote-business-state-fee.md) just before.
- Stored as `byte[]` (`pdf.decode`) for the history-flush size limit.
