# Service task: `transport-certificate-pdf`

**BPMN task:** `Task_TransportCertificatePdf`
**Kind:** http-connector → pdf-renderer (Gotenberg)
**Async:** `asyncBefore="true"`

Generates the vehicle registration certificate ("Mulkiya"-style document)
— the demo's tangible result of the service.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/render` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-certificate-pdf.json.ftl` —
`{ "html": "...", "filename": "..." }` for the pdf-renderer sidecar.
Variables in scope: `applicantName`, `civilId`, `vin`, `vehicleCategory`,
`registrationType`, `plateNumber`, `registrationFee`, `execution`.

The HTML document: Transport Authority letterhead (text
only, no images), title "Vehicle Registration Certificate", a table with
plate number (large, prominent), owner name + civil number, VIN, vehicle
category, registration type, fee paid (EUR, 3 decimals, defensive numeric
coercion), case reference (`execution.processInstanceId`), and issue date
(rendered server-side by FreeMarker `.now`). Filename:
`transport-registration-certificate.pdf`.

## Response → output parameters

Response body: `{ "filename": "...", "base64": "..." }`

| Output variable | Type | Expression |
|---|---|---|
| `certificatePdfBytes` | byte[] | `${pdf.decode(S(response).prop('base64').stringValue())}` — stored bytes to spill to ACT_GE_BYTEARRAY |
| `certificatePdfFilename` | String | `${S(response).prop('filename').stringValue()}` |

## Why these notes matter

- `byte[]`, never a base64 String variable — String process variables cap
  at 4000 chars in `ACT_HI_VARINST.TEXT_` (project memory: large process
  variables must be bytes).
- HTML is inlined in the JSON via the template; escape with
  `?json_string` after assembling the HTML `<#assign>` block.
