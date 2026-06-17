# Service task: `transport-permit-email`

**BPMN task:** `Task_TransportPermitEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo step 5: *"Send notification of receipt of electronic driving license
and payment receipt (via e-mail, SMS and notification, applications)."*
Email-only in the POC; the license-plus-receipt PDF is attached.

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${busBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-permit-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `permitNumber`,
`permitValidUntil`, `licenseCategory`, `permitPdfBytes`,
`permitPdfFilename`.

Body: congratulations, permit number, category, valid-until date, and a
note that the attached PDF is the electronic license with the payment
receipt. Attachment via `${pdf.encode(permitPdfBytes)}`. Sender "Transport Authority"; subject "Your driving learning
license ${permitNumber}".

## Response

Fire-and-forget.
