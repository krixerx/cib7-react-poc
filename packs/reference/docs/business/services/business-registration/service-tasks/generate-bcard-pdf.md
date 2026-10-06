# Service task: `generate-bcard-pdf`

**Task id:** `generate-bcard-pdf`
**BPMN task id:** `Task_GenerateBcardPdf`
**Display name:** `Generate B-card extract`
**Connector:** `http-connector`
**Async-before:** `true`

Renders the B-card extract, the company's registration certificate, once the state fee is paid.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/render` |
| Headers | `Content-Type: application/json` |

Downstream: pdf-renderer, which renders HTML to PDF with Gotenberg.

## Payload (request body)

```
payload-template: bcard-pdf.json.ftl
```

**Document:** `documents/pdf/bcard-extract.ftlh`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  pdf-renderer /render payload for the B-card extract task in business-registration.bpmn: the B-card extract.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/bcard-extract.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("bcard-extract", execution)?json_string}",
  "filename": "bcard-extract-${(companyName!"OU")?json_string}.pdf"
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `bcardPdfBytes` | `byte[]` | `${pdf.decode(S(response).prop('base64').stringValue())}` |
| `bcardPdfFilename` | `String` | `${S(response).prop('filename').stringValue()}` |

## Notes

- Stored as `byte[]` for the history-flush size limit.
