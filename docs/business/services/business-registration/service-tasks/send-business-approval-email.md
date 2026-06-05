# Service task: `send-business-approval-email`

**BPMN task:** `Task_SendApprovalEmail`
**Kind:** http-connector → Mailpit
**Async:** `asyncBefore="true"` (runs on the job executor after the
preceding gateway)

## Endpoint

| HTTP | URL | Headers |
|---|---|---|
| POST | `${mailApiBaseUrl}/api/v1/send` | `Content-Type: application/json` |

`${mailApiBaseUrl}` resolves from `MailConfiguration.java`
(`MAIL_API_URL` env var; defaults to `http://mailpit:8025` in compose,
`http://localhost:8025` for `mvn spring-boot:run`).

## Payload

FreeMarker template at `cib7/src/main/resources/templates/business-approval-email.json.ftl`.
Variables in scope: `companyName`, `shareCapital`, `applicantFirstName`,
`applicantLastName`, `boardMembers` (Spin Json), `autoDecision`,
`decision`.

The template renders the Mailpit `/api/v1/send` JSON payload — `From`,
`To`, `Subject`, `Text`. The `Text` body includes the applicant name,
company name, share capital, and a list of board members rendered from
the `boardMembers` Spin Json list.

## Response

Mailpit returns a JSON `{ID: "...", Total: N}`. We don't capture the ID
into a process variable for this POC — fire-and-forget is fine because
Mailpit is non-persistent and the process semantics don't depend on
the email landing.

## Why these notes matter

- The `applicantEmail` variable is NOT written by the applicant form in
  this POC (the form collects only the data the BPMN/DMN needs). The
  approval email goes to a deterministic test address
  (`applicant@cib7-poc.local`) so Mailpit always receives it. Production
  would add a real applicant email field.
- The template escapes all string values with `?json_string` for JSON
  safety per the skill's FreeMarker rules.
