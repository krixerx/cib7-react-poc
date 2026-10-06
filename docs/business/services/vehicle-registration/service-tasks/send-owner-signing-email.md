# Service task: `send-owner-signing-email`

**Task id:** `send-owner-signing-email`
**BPMN task id:** `Task_SendOwnerConfirmEmail`
**Display name:** `Send co-owner signing email`
**Connector:** `http-connector`
**Async-before:** `true`

Asks one co-owner to review the registration and sign or reject it.

## Request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `${busBaseUrl}/api/v1/send` |
| Headers | `Content-Type: application/json` |

Downstream: Mailpit (the bus routes `/api/v1/send`).

## Payload (request body)

```
payload-template: owner-confirmation-email.json.ftl
```

**Document:** `documents/email/vehicle-owner-signing.ftl`, hand-designed and owned by the pack, not generated. The template only wraps it.

```ftl
<#--
  Mailpit /api/v1/send payload for the "Send co-owner signing email"
  service task inside SubProcess_OwnerConfirmations in
  vehicle-registration.bpmn.

  Scope: this template runs inside one multi-instance subprocess iteration.
  The per-iteration element variable `owner` is a SpinJsonNode with prop()
  accessors for partyId / name / email (ConsentPartiesListener wrote it).
  Process-scope variables (firstName, lastName) and the reserved beans
  frontendBaseUrl and links are also in scope.

  The confirmation token is minted here by links.consent(execution, "owner", partyId)
  and never stored: an HMAC over case, party, consentRound and expiry
  (docs/security.md rule 3).

  ?json_string escapes embedded quotes / backslashes / newlines so the
  emitted payload is always valid JSON regardless of what the applicant
  typed into the co-owner editor.

  The body is the pack document documents/email/vehicle-owner-signing.ftl.
-->
<#assign ownerName = owner.prop("name").stringValue()>
<#assign ownerEmail = owner.prop("email").stringValue()>
<#assign applicantName = (firstName!"") + " " + (lastName!"")>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transpordiamet POC" },
  "To": [ { "Email": "${ownerEmail?json_string}", "Name": "${ownerName?json_string}" } ],
  "Subject": "Please sign: vehicle registration with ${applicantName?json_string}",
  "Text": "${documents.text("vehicle-owner-signing", execution)?json_string}"
}
```

## Response mapping

None: fire-and-forget. The case does not depend on the response body; a failed call becomes an incident after the engine's retries.

## Notes

- Runs inside the co-owner signatures subprocess, once per element of `additionalOwners` (parallel multi-instance, element variable `owner`).
- The link carries a capability token from `links.owner(execution, owner.partyId)`; the token, never a stored id, is the credential of the public confirmation page (docs/security.md rule 3).
- Co-owner names are user input; the document is plain text and the payload escapes it with `?json_string`.
