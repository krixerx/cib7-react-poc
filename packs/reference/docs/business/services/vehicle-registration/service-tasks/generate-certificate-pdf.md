# Service task: `generate-certificate-pdf`

**Task id:** `generate-certificate-pdf`
**BPMN task id:** `Task_GenerateCertificatePdf`
**Display name:** `Generate vehicle registration certificate`
**Connector:** `http-connector`
**Async-before:** `true`

Renders the vehicle registration certificate once the state fee is paid.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/render` |
| Headers | `Content-Type: application/json` |

Downstream: pdf-renderer, which renders HTML to PDF with Gotenberg.

## Payload (request body)

```
payload-template: certificate-pdf.json.ftl
```

**Document:** `documents/pdf/vehicle-certificate.ftlh`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  pdf-renderer /render payload for Task_GenerateCertificatePdf in vehicle-registration.bpmn: the Vehicle Registration Certificate.

  The document itself (layout, values, fee rules) is the pack's
  documents/pdf/vehicle-certificate.ftlh, rendered by the engine's `documents` bean on
  the shared documents/_brand.ftlh layout; this template only wraps it
  in JSON (?json_string) with a file name.
-->
{
  "html": "${documents.html("vehicle-certificate", execution)?json_string}",
  "filename": "vehicle-registration-certificate-${execution.processInstanceId?json_string}.pdf"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "initiator": "lisa",
  "firstName": "Ants",
  "lastName": "Avaldaja",
  "vehicleMake": "Škoda",
  "vehicleModel": "Octavia",
  "objectId": "VIN-1234567",
  "price": 38000,
  "additionalOwners": [
    {
      "name": "Olga Omanik",
      "email": "olga@example.com",
      "partyId": "p1"
    }
  ],
  "owner": {
    "name": "Olga Omanik",
    "email": "olga@example.com",
    "partyId": "p1"
  }
}
```

## Response mapping

| Output process variable | Type | Expression |
|---|---|---|
| `certificatePdfBytes` | `byte[]` | `${pdf.decode(S(response).prop('base64').stringValue())}` |
| `certificatePdfFilename` | `String` | `${S(response).prop('filename').stringValue()}` |

## Notes

- Stored as `byte[]` for the same reason as the invoice.
