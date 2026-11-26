<!--
  Service-task spec — one file per BPMN service task. Filename = task id
  (kebab-case, must match the id used in the README flow).

  The service-builder skill emits a <bpmn:serviceTask> with an inline
  <camunda:connector> (http-connector) and, if `payload-template` is set
  below, a FreeMarker file at <pack>/engine/templates/<task-id>.json.ftl.

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
| URL | `${patchbayBaseUrl}/api/v1/integrations/<slug>` \| `${backendBaseUrl}/api/internal/<path>` |
| Headers | `Accept: application/json`, `Content-Type: application/json`, … |

Downstream: <Patchbay integration `mail-send` (v1) | Patchbay integration `pdf-render` (v1) | backend <module> (the engine adds `X-Internal-Token` on `/api/internal/`)>.

A URL has one of these shapes and no query, fragment or `..`. **Don't
hard-code** a system URL, and **don't** set an `X-Internal-Token` or
`X-API-Key` header: the engine adds `X-API-Key` on Patchbay's integrations and
`X-Internal-Token` on the backend's `/api/internal/` itself.

| URL | Downstream | When |
|---|---|---|
| `${patchbayBaseUrl}/api/v1/integrations/mail-send` | core integration `mail-send` v1 | sending mail |
| `${patchbayBaseUrl}/api/v1/integrations/pdf-render` | core integration `pdf-render` v1 | rendering PDFs |
| `${patchbayBaseUrl}/api/v1/integrations/pack-<name>` | an integration in the pack's `patchbay/<package>/` | the pack's own external system |
| `${backendBaseUrl}/api/internal/**` | backend | business calls / document writes (engine-only) |
| `${backendBaseUrl}/api/public/**` | backend | public reference data without personal data |

A NEW external system is a Patchbay integration exported into the pack's
`patchbay/<package>/` with `pack-` names, not a literal URL or a new
`*Configuration.java` bean.

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
`<pack>/engine/templates/<task-id>.json.ftl` and references it from
the BPMN via `<camunda:script scriptFormat="freemarker" resource="…" />`.

Required template rules:

- Escape every string with `?json_string`.
- Default every process variable that might be null: `${(varName!"")}`
  (string) or `${(varName!0)}` (number).
- For `byte[]` attachments, re-encode with `${pdf.encode(varName)}`.
- Follow the integration's neutral model, never a vendor's schema:
  `mail-send` v1 takes `from {email, name?}`, `to [{email, name?}]`,
  `subject`, `text` and optional `attachments [{filename, contentType, base64}]`;
  `pdf-render` v1 takes `{html, filename}` and answers `{filename, base64}`.

```ftl
<#-- describe the variables in scope -->
{
  "field": "${(varName!"")?json_string}"
}
```

## Example

Required with a payload template. The core's pack checks render the
template with these case variables (`TemplateExamplesTest`); an object or a
list becomes a Spin JSON variable, `{"$bytes": "text"}` a `byte[]`. The core
adds `execution`, `pdf`, `links`, `documents` and `frontendBaseUrl`. It also
renders with a quote, a backslash, a line break and markup appended to every
string of text, which must still give valid JSON. Optional expectations: a
JSON pointer and a JSON literal it equals, `contains "text"` or
`matches "regex"`. Add `## Example: <case>` sections for further cases.

```json
{"<variable>": "<value>"}
```

| Path | Expected |
|---|---|
| `/<field>` | `contains "<text>"` |

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
