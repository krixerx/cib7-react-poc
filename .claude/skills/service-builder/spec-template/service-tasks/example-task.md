<!--
  Service-task spec — one file per BPMN service task. Filename = task id
  (kebab-case, must match the id used in the README flow).

  The service-builder skill emits a <bpmn:serviceTask> with an inline
  <camunda:connector> (http-connector) and, if `payload-template` is set
  below, a FreeMarker file at cib7/src/main/resources/templates/<task-id>.json.ftl.

  Replace ALL `<…>` placeholders. Delete sections that don't apply.
-->

# Service task: `<task-id>`

**Task id:** `<task-id>` (kebab-case, globally unique)
**BPMN task id:** `Task_<PascalCase>` (the id used inside the BPMN file)
**Display name:** `<Name shown in Cockpit / mermaid>`
**Connector:** `http-connector`
**Async-before:** `true` | `false` (default `true` — lets the engine acknowledge
  the previous step before this one runs)

## Request

| Field | Value |
|---|---|
| Method | `GET` \| `POST` \| `PUT` \| `DELETE` |
| URL | `${baseUrlVar}/path/${someProcessVar}` |
| Headers | `Accept: application/json`, `Content-Type: application/json`, … |

Use the engine's exposed JUEL beans for base URLs — **don't hard-code**:

| JUEL | When | Pattern |
|---|---|---|
| `${mailApiBaseUrl}` | sending mail via Mailpit | `/api/v1/send` |
| `${pdfApiBaseUrl}` | rendering PDFs via pdf-renderer | `/render` |

If the request targets a new external system, ask the platform team to add a
`*Configuration.java` bean for the base URL — don't inline the literal URL.

## Payload (request body)

Pick **one** of the two patterns:

### A. Inline payload (short, no FreeMarker)

```
payload-inline:
{
  "key": "${someVar}",
  "other": "literal"
}
```

The builder emits this verbatim inside `<camunda:inputParameter name="payload">`.
For anything longer than ~10 lines or anything that needs conditionals,
loops, or string escaping, use pattern B instead.

### B. FreeMarker template

```
payload-template: <task-id>.json.ftl
```

Then provide the template body below. The builder writes it to
`cib7/src/main/resources/templates/<task-id>.json.ftl` and references it from
the BPMN via `<camunda:script scriptFormat="freemarker" resource="…" />`.

Required template rules:

- Escape every string with `?json_string`.
- Default every process variable that might be null: `${(varName!"")}`
  (string) or `${(varName!0)}` (number).
- For `byte[]` attachments, re-encode with `${pdf.encode(varName)}`.

```ftl
<#-- describe the variables in scope -->
{
  "field": "${(varName!"")?json_string}"
}
```

## Response mapping

How the engine reads the response. The raw body comes back in the implicit
`response` variable; use Spin (`S(response)`) to navigate it.

| Output process variable | Type | Expression |
|---|---|---|
| `<varName>` | `String` | `${S(response).prop('data').prop('field').stringValue()}` |
| `<varName>` | `Double` | `${S(response).prop('data').prop('price').numberValue()}` |
| `<varName>` | `byte[]` | `${pdf.decode(S(response).prop('base64').stringValue())}` |

For tasks that fire-and-forget (notification emails), this section can be empty.

## Notes

<Why this task is asyncBefore, any retry / timeout / idempotency concerns,
links to the external API docs.>
