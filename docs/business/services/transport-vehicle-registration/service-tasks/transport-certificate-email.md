# Service task: `transport-certificate-email`

**BPMN task:** `Task_TransportCertificateEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"`

Demo step 5: *"Sending a notification to receive the vehicle license (via
email, SMS, and application notification)."* Email-only in the POC; the
registration certificate PDF is attached and the body includes
plate-collection instructions (demo step 4, "Receiving vehicle plates").

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

## Payload

FreeMarker template at
`cib7/src/main/resources/templates/transport-certificate-email.json.ftl`.
Variables in scope: `applicantName`, `applicantEmail`, `plateNumber`,
`vin`, `vehicleCategory`, `certificatePdfBytes`, `certificatePdfFilename`.

Body: congratulations, the allocated plate number, instructions to
collect the physical plates from the license-plate printing company /
service center, and a note that the attached certificate is the official
registration document. Attachment block:

```json
"Attachments": [{
  "Filename": "${(certificatePdfFilename!"transport-registration-certificate.pdf")?json_string}",
  "ContentType": "application/pdf",
  "Content": "${pdf.encode(certificatePdfBytes)}"
}]
```

Sender "Transport Authority"; subject
"Your vehicle is registered — plate ${plateNumber}".

## Response

Fire-and-forget.
