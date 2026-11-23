# Service task: `send-founder-signing-email`

**Task id:** `send-founder-signing-email`
**BPMN task id:** `Task_SendFounderSigningEmail`
**Display name:** `Send co-founder signing email`
**Connector:** `http-connector`
**Async-before:** `true`

Asks one co-founder to review the founding details and sign or reject the Articles of Association.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/v1/send` |
| Headers | `Content-Type: application/json` |

Downstream: Mailpit (the bus routes `/api/v1/send`).

## Payload (request body)

```
payload-template: founder-signing-email.json.ftl
```

**Document:** `documents/email/business-founder-signing.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  Mailpit /api/v1/send payload for the "Send co-founder signing email"
  service task inside SubProcess_FounderSignatures in
  business-registration.bpmn.

  Scope: this template runs inside one multi-instance subprocess iteration.
  The per-iteration element variable `founder` is a SpinJsonNode with prop()
  accessors for partyId / name / email (ConsentPartiesListener wrote it).
  Process-scope variables (applicantFirstName, applicantLastName,
  companyName) and the reserved beans frontendBaseUrl and links are also in
  scope.

  The signing token is minted here by links.consent(execution, "founder", partyId) and
  never stored: an HMAC over case, party, consentRound and expiry
  (docs/security.md rule 3).

  ?json_string escapes embedded quotes / backslashes / newlines so the
  emitted payload is always valid JSON regardless of what the applicant
  typed into the co-founder editor.

  The body is the pack document documents/email/business-founder-signing.ftl.
-->
<#assign founderName = founder.prop("name").stringValue()>
<#assign founderEmail = founder.prop("email").stringValue()>
<#assign applicantName = (applicantFirstName!"") + " " + (applicantLastName!"")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Äriregister POC" },
  "To": [ { "Email": "${founderEmail?json_string}", "Name": "${founderName?json_string}" } ],
  "Subject": "${("Please sign: " + (companyName!"") + " (founder: " + applicantName + ")")?json_string}",
  "Text": "${documents.text("business-founder-signing", execution)?json_string}"
}
```

## Example

Rendered with these case variables by the core's pack checks
(`TemplateExamplesTest`), also with a hostile suffix on every string.

```json
{
  "applicantFirstName": "Frida",
  "applicantLastName": "Asutaja",
  "companyName": "Näidis OÜ",
  "founder": {
    "name": "Karl Kaasasutaja",
    "email": "karl@example.com",
    "partyId": "p1"
  }
}
```

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Runs inside the co-founder signatures subprocess, once per element of `additionalFounders` (parallel multi-instance, element variable `founder`).
- The link carries a capability token from `links.consent(execution, "founder", founder.partyId)` (docs/security.md rule 3).
