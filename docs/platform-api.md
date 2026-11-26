# Platform API

**Platform API version:** `3.0`

`3.0` replaces the integration bus with Patchbay. What broke: the
`busBaseUrl` bean is gone, and a connector calls either
`${patchbayBaseUrl}/api/v1/integrations/<slug>` (mail, PDF, the pack's own
integrations) or `${backendBaseUrl}/api/...` (the backend paths listed below);
mail and PDF payloads are the core's own models `mail-send` and `pdf-render`
instead of Mailpit's and the PDF renderer's schemas; a pack's own integrations
are Patchbay packages in `patchbay/<package>/` instead of
`esb/routes/pack-<name>.yaml`, and the `/pack/<name>/...` bus paths are gone;
backend descriptors say `platform: 3`. A `2.x` pack is refused at startup,
and the pack check names `3.0` when a BPMN still uses `${busBaseUrl}`.

`2.0` shipped with core 2.0.0 (2026-10-07); core 2.0.1 ran it too, and it
held until `3.0`. While it was being written, before that release, it changed without a major bump: `links.consent(execution, purpose, partyId)` replaced
`links.owner` / `links.founder`, policies gained `identity`, packs gained
`backend/payment/` and `backend/documents.json`, the two consent pages
became one, `/consent/<purpose>/<token>`, worded by the pack, and the MCP
manifests became format 2: texts and offered fields only, with the value rules
taken from the form schemas (and `notOffered`), and the specs gained their
service examples.

**When to read this:** before writing or changing anything in a service pack,
and before changing core code that reads pack files. This is the contract
between the core (this repository's modules) and a service pack (one
directory `packs/<pack>/` per customer; the reference pack, `packs/reference/`,
is the one the core's own tests run on). The core reads a pack only through
the formats below; a pack reaches the core only through them. A new customer's
pack is a new directory under `packs/` with the same layout.

Contents: [Versioning](#versioning) · [Pack layout](#pack-layout) ·
[Formats](#formats) · [What templates and documents see](#what-templates-and-documents-see) ·
[Integrations offered to a pack](#integrations-offered-to-a-pack) · [Brand tokens](#brand-tokens) · [Shared value rules](#shared-value-rules) ·
[What a pack cannot do](#what-a-pack-cannot-do) · [Checking a pack](#checking-a-pack) ·
[Not in the platform API yet](#not-in-the-platform-api-yet)

## Versioning

The platform API has a `major.minor` version, stated above and in
`PackManifest.PLATFORM_MAJOR` / `PLATFORM_MINOR` in the engine and the backend
(a test holds all three equal).

- **A minor only adds:** an optional key, a new field type, a new bean, a new
  token. A pack built for `3.0` runs on every `3.x`.
- **A major may break:** a key renamed or removed, a meaning changed. Every
  pack under `packs/` moves to it in the same change, by regenerating with
  the service-builder.
- **The service-builder writes for the current platform API.** It lives in
  `.claude/skills/service-builder/` and changes in the same commit as the
  formats it writes.
- **The pack states what it needs** in `pack.yaml` (`platform: "3.0"`). The
  engine (before deploying anything) and the backend (before reading any
  descriptor) refuse to start with another major or a newer minor, so a
  pack image built on mismatched core images fails at startup.
- **Formats also carry their own version** where they are read by the SPA,
  which cannot read `pack.yaml`: form definitions, the catalog, `brand.json`
  and `tokens.json` say `"version": 1` and are refused whole at another
  value. Backend descriptors say `platform: 3`, the major they are written
  for.

Every format is **strict**: an unknown key or a value outside its pattern
refuses the file (or the start), never half of it.

## Pack layout

```
<pack>/
├── pack.yaml                         manifest: name, version, platform
├── docs/business/services/<service>/  the specs (source of truth) and build/ (MCP manifests)
├── engine/                           on the engine's classpath (/opt/services)
│   ├── processes/<service>/          one engine deployment per folder
│   │   ├── <service>.bpmn, *.dmn
│   │   ├── variable-policy.json
│   │   └── schemas/<form-id>.json, schemas/start.json
│   ├── templates/*.json.ftl          connector payloads
│   └── documents/                    _brand.ftlh, pdf/*.ftlh, email/*.ftl
├── backend/                          on the backend's classpath (/opt/services)
│   ├── registry/<entity>.yaml
│   ├── db/registry/V<n>__*.sql
│   └── consent/<purpose>.yaml
├── keycloak/cib7-poc-users-0.json    the realm's users, imported into the core realm
├── frontend/                         served at /pack/
│   ├── catalog.json
│   ├── forms/<form-id>.json
│   └── locales/<lang>/<namespace>.json
├── patchbay/<package>/               the pack's own integrations, each the unpacked content of a
│                                     Patchbay export; may be empty (a .gitkeep)
├── docker/<image>.Dockerfile         the pack's images: each FROM a core image (a build argument
│                                     such as ENGINE_CORE names it), adds the pack's files
│                                     (frontend, engine, backend, mcp, mobile, patchbay);
│                                     patchbay.Dockerfile (PATCHBAY_CORE) zips each folder of
│                                     patchbay/ into /import/50-<package>.zip, after the core's
│                                     packages. Keycloak runs the stock
│                                     image: the core realm and theme and the pack's users
│                                     file and branding are mounted
└── branding/                         served at /pack/branding/, engine classpath branding/
    ├── brand.json, tokens.json, logo and favicon images
    └── locales/<lang>/brand.json
```

A pack carries no tooling of its own: the service-builder, `pack-check.sh`
and the workflows that build its layers (`docker-publish.yml`) and deploy it
(`deploy.yml`, variable `PACK`) are the repository's. A pack has a short
`README.md`.

The pack's specs, the source of everything generated in it, live in its
`docs/business/services/<service>/`, together with the generated MCP
manifests (`<service>/build/`, `build/services.json`). Its own
integrations, when it has any, are Patchbay packages in `patchbay/<package>/`
([Integrations offered to a pack](#integrations-offered-to-a-pack)). The
reference pack has none and keeps an empty `patchbay/`, so every pack builds
the same `patchbay` layer.

## Formats

| Path in the pack | Format | Read by | Checked by |
|---|---|---|---|
| `pack.yaml` | YAML: `name` (kebab), `version` (`x.y.z`), `platform` (`"M.m"`) | `PackManifest` (engine, backend) | `PackManifestTest` |
| `engine/processes/<service>/*.bpmn` | BPMN 2.0, `camunda:` namespace; user tasks `camunda:formKey="react:<form-id>"`; `candidateGroups` without a leading slash; connectors only to the [destinations offered to a pack](#integrations-offered-to-a-pack), with no `X-API-Key` or `X-Internal-Token` header; business rule tasks `decisionRefBinding="deployment"` | `ServiceDeployments` | `PackConformanceTest`, `PackIntegrationTest`, `ServiceSpecsTest` |
| `engine/processes/<service>/*.dmn` | DMN 1.3 with `camunda:historyTimeToLive` | the engine, in the service's deployment | `PackConformanceTest` |
| `engine/processes/<service>/variable-policy.json` | `processDefinitionKey`, `start` and `forms.<form-id>`: the variables a client may write; `identity`: variable to `givenName`, `familyName` or `email`, set from the account and re-checked on completion | `VariablePolicyRegistry`, `VariableWritePolicyFilter`, `IdentityFieldRegistry` | `VariablePolicyFilesTest`, `IdentityFieldRegistryTest` |
| `engine/processes/<service>/schemas/*.json` | JSON Schema 2020-12 with `x-process` and `x-form`; may `$ref` the [shared value rules](#shared-value-rules) | `FormSchemaRegistry` | `FormSchemaRegistryTest` |
| `engine/templates/*.json.ftl` | FreeMarker producing JSON; every string `?json_string`; no `<#include>` | the engine's FreeMarker script engine (BPMN `resource=`) | `PackConformanceTest`, `ServiceSpecsTest` |
| `engine/documents/` | `pdf/<name>.ftlh` (HTML, escaped), `email/<name>.ftl` (text), shared `_brand.ftlh` | `DocumentRenderer` (`documents` bean) | `PackConformanceTest` |
| `backend/registry/<entity>.yaml` | registry descriptor, `platform: 3`: entity, table, key, sort, typed fields, derived fields, operations with access `public` or `internal` | `RegistryCatalog`, `RegistryController` | `BackendApplicationSmokeTest` |
| `backend/db/registry/V<n>__*.sql` | Flyway migrations in the `registry` schema, own history table | `RegistryMigrations` | `BackendApplicationSmokeTest` (columns against descriptors) |
| `backend/consent/<purpose>.yaml` | co-signing descriptor, `platform: 3`: process, variables, messages, wording | `ConsentCatalog`, `ConsentController` | `ConsentCatalogTest`; `ConsentTextsTest` (the page texts in `frontend/locales/<lang>/consent.json` for every purpose, detail and document) |
| `backend/payment/<service>.yaml` | state fee, `platform: 3`: process, fee name, recipient, currency, `amount` flat or tiered by one engine-set variable | `FeeCatalog`, `FeeSchedule` (checkout, callback, `/api/internal/payments/quote/<id>`) | `FeeCatalogTest`; `PackConformanceTest` (tier variable not client-writable) |
| `backend/documents.json` | document categories, `platform: 3`: `by: applicant` (a signed-in user may upload) or `by: system` (only the engine files it; name starts `generated-`); the core adds `generated-certificate` | `DocumentCategories` (user endpoints accept only applicant categories, `server-upload` only system ones) | `DocumentCategoriesTest`; `PackConformanceTest` and `src/pack/pack.test.ts` (every category used is declared and labelled) |
| `keycloak/<realm>-users-0.json` | Keycloak users file (`realm`, `users`), imported after the core realm `keycloak/cib7-poc-realm.json` from the same directory; users join core groups only (`/applicant`, `/civil-servant`, `/cib7-admin`) and get their roles from them | Keycloak (`--import-realm`) | `RealmFilesTest` |
| `frontend/catalog.json` | catalog v1: namespaces, services (category, issuer), issuers (tone) | `src/pack/catalog.ts` | `src/pack/pack.test.ts` |
| `frontend/forms/<form-id>.json` | form definition v1 | `src/forms/schema/definition.ts` | `src/pack/pack.test.ts` |
| `frontend/locales/<lang>/<ns>.json` | i18next JSON; `catalog`, `names` and one namespace per form; `en` and `ar` with the same keys | `loadPack()`; the mobile app reads `catalog` (document labels) | `src/pack/pack.test.ts` (also: every BPMN `name=` translated) |
| `branding/brand.json` | brand v1: `logo.light`, `logo.dark`, `favicon` (bare image file names) | `src/pack/brand.ts`, `DocumentBrand`, the login theme (`keycloak/themes/cib7/login/template.ftl`) | `src/pack/brand.test.ts` |
| `branding/tokens.json` | brand v1: [brand tokens](#brand-tokens) per scheme (hex), `fonts.display` / `fonts.body` (family names) | `src/pack/brand.ts`, `DocumentBrand`, the login theme, the mobile app (`mobile/lib/pack.dart`) | `src/pack/brand.test.ts` (with WCAG AA contrast) |
| `branding/locales/<lang>/brand.json` | `name`, `sub`, `portal` | `loadBrand()`, `DocumentBrand`, the login theme, the mobile app (`mobile/lib/pack.dart`) | `src/pack/brand.test.ts` |
| `patchbay/<package>/` | the unpacked content of a Patchbay export: `manifest.json` (`format: "patchbay-package"`, `formatVersion: 1`), `entities/` (payload definitions), `services/<slug>.json` with `services/<slug>.versions/<version>.json`, `contracts/`; every slug, payload definition id, contract id and connection id starts with `pack-`; no `connections/` or `secrets/`; no `target.baseUrl` in a draft or version. Changed only by publishing in a Patchbay console and exporting over the folder, never by editing a version file | Patchbay (`MAPPER_IMPORT_DIR`, after the core packages) | `PackIntegrationTest` |
| `docs/business/services/<service>/build/mcp-service.json` | MCP manifest, `version: 2`: `key` (the process), texts, `start.fields` and per user task `fields` (offered field to its description for the agent), `notOffered` (the policy's other fields, written only by the portal form), `requiredDocuments`; no value rules: those are the engine's `schemas/<form-id>.json` | `mcp/src/services/manifest.ts` | `mcp/src/services/pack.test.ts` (every field in the form schema, every required field offered, every form covered); `VariablePolicyFilesTest` (policy = `fields` + `notOffered`) |
| `docs/business/services/<service>/build/mcp-training.md`, `docs/business/services/build/services.json` | Markdown guidance for the agent; the services index | `mcp/src/server.ts` | |

The details of each format sit with its reader: the Javadoc or JSDoc of the
class named above, and for generated files the service-builder skill
(`.claude/skills/service-builder/SKILL.md`).

## What templates and documents see

| Name | In | What it is |
|---|---|---|
| case variables | templates, documents | the process variables in scope, loop variables included |
| `execution` | templates, documents | the current execution (`processInstanceId`, …) |
| `patchbayBaseUrl` | BPMN, templates | Patchbay, the address of every [integration](#integrations-offered-to-a-pack) |
| `backendBaseUrl` | BPMN, templates | the backend, for the [backend paths](#backend-paths) offered to a pack |
| `frontendBaseUrl` | BPMN, templates, documents | the portal's public URL, for links in emails |
| `links` | templates, documents | mints capability tokens: `consent(execution, purpose, partyId)` for a purpose in `backend/consent/`, `payment(execution)` |
| `pdf` | BPMN, templates | `decode(base64)` to `byte[]`, `encode(byte[])` to base64 |
| `documents` | templates | `html("<name>", execution)`, `text("<name>", execution)` |
| `brand` | documents | `name`, `primary`, `logo` (data URI or null) |
| `S(…)` | BPMN, templates | Spin JSON |

These names win over case variables of the same name
(`ReservedBeansPlugin.RESERVED_NAMES`), so a client cannot redirect a
connector or a link by writing a variable. No other Spring bean resolves by
name in an expression or a template (`BeanExposureTest`).

## Integrations offered to a pack

A connector's `url` is one of two destinations, a plain value with no query,
fragment or `..` (`PackIntegrationTest`):

- `${patchbayBaseUrl}/api/v1/integrations/<slug>`, where `<slug>` is a core
  integration (`mail-send`, `pdf-render`) or a `pack-` slug that one of the
  pack's own packages declares;
- `${backendBaseUrl}<path>` for one of the [backend paths](#backend-paths).

A connector sets no credential. The engine adds `X-API-Key` only to calls
under Patchbay's `/api/v1/integrations/` and `X-Internal-Token` only to calls
under the backend's `/api/internal/`, and removes both from every other
request, a header the BPMN set included (`OutboundCredentialsInterceptor`,
`OutboundCredentialsInterceptorTest`). The pack check refuses a connector
that sets either header.

### `mail-send` v1

Sends one email. Patchbay maps it to the deployment's mail system (Mailpit
here).

| Field | Type | Required |
|---|---|---|
| `from` | object | yes |
| `from.email` | text | yes |
| `from.name` | text | no |
| `to` | list of objects, one per recipient | yes |
| `to[].email` | text | yes |
| `to[].name` | text | no |
| `subject` | text | yes |
| `text` | text, the plain-text body | yes |
| `attachments` | list of objects | no |
| `attachments[].filename` | text | yes |
| `attachments[].contentType` | text, a media type | yes |
| `attachments[].base64` | text, the file's bytes in base64 | yes |

The answer is `{ "messageId": "…" }` (optional; the mail system's id for the
message). `to` is a list even for one recipient. `from` is in the model
because the sender name is the pack's brand. There is no `html` body in v1.
Text passes through unchanged, so any language works; every value in the
template still goes through `?json_string`.

Where a call is refused: Patchbay refuses a call without `to` with 400 and
the field named (`"problems": ["to: required"]`), before the mail system is
called; that it checks the required fields inside list items is not verified.
It checks that a field is present, not that a list has items: `"to": []` passes Patchbay, and the mail system refuses it with 400
(Mailpit: `no recipients`), which comes back to the engine as the
connector's answer; no mail is sent either way. A service task whose
recipient can be missing is guarded in the BPMN, before the call.

### `pdf-render` v1

Renders one HTML document to PDF. Patchbay maps it to the core PDF renderer.

| Field | Type | Required |
|---|---|---|
| `html` | text, a whole HTML document (`documents.html(...)`) | yes |
| `filename` | text | yes |

The answer is `{ "filename": "…", "base64": "…" }` (both optional in the
model), the rendered file's bytes in base64; a response mapping reads it as
`${pdf.decode(S(response).prop('base64').stringValue())}`.

### Versioning of the models

The models are part of the platform API. Adding an optional field is a
minor; adding a required field, renaming or removing a field, changing its
type, or renaming a slug is a major. A change to how Patchbay maps a model
onto the external system, with the model unchanged, is a new version of the
core package only (`patchbay/packages/`) and no platform API change.

### A pack's own integrations

A pack adds an external system as a Patchbay package in
`patchbay/<package>/` ([format](#formats)), built and tested in a local
Patchbay console of the release the core image pins, published there,
exported, and unpacked over the folder. Its ids start with `pack-`, so it can
never replace a core definition or integration on import. It holds no
address and no secret: those come from the deployment's environment, in
variables `MAPPER_URL_PACK_*`, `MAPPER_HEADER_PACK_*` and
`MAPPER_SECRET_PACK_*` in the host's `patchbay-pack.env`. The deploy refuses
that file when it holds another key or a URL naming a core service, and
Patchbay shares no network with the backend, Keycloak, Postgres, RustFS or
Graylog.

### Backend paths

`/api/internal/documents/move-pending` and
`/api/internal/documents/server-upload` (case documents),
`/api/{public,internal}/registry/<entity>` (its registries),
`/api/internal/payments/quote/<processInstanceId>` (the case's state fee, as
`amount` and `currency`). A registry path names a registry the pack
declares, in an access class one of its operations has.

## Brand tokens

`tokens.json` may set, per colour scheme: `primary`, `primary-hover`,
`primary-ink`, `primary-soft`, `primary-soft-border`, `mesh-1` to `mesh-4`,
`banner-bg`, `banner-fg`, `banner-strong`; and the fonts `display` and
`body`. Every other token in `frontend/src/styles/tokens.css` is core. The
list lives in `BRAND_COLOR_TOKENS` / `BRAND_FONT_TOKENS`
(`frontend/src/pack/brand.ts`); adding a name is a minor change, removing one
a major.

## Service examples

A pack carries its services' tests as data in the specs, and the core runs
them in `scripts/pack-check.sh`, so a service's expected behaviour travels
with the pack and holds on every core change:

| In the spec | Run by | Against |
|---|---|---|
| `decisions/<id>.md` `## Examples` (and `## Examples without \`<rule id>\``) | `DecisionExamplesTest` (engine) | the pack's DMN, each row matching exactly one rule |
| README `### Fee examples` under `## State fee` | `FeeExamplesTest` (backend) | the backend's `FeeSchedule` on `payment/<service>.yaml`, plus the section's recipient and currency |
| `data/<entity>.md` `## Seed` | `RegistrySeedTest` (backend) | every row read back through the registry endpoint class each operation declares |
| `forms/<id>.md` `## Submission examples` | `SubmissionExamplesTest` (engine) | the form's value schema, the one the engine checks completions with |
| `forms/<id>.md` `## Behaviour examples` | `src/pack/behaviour.test.ts` (frontend) | the portal's form renderer on `frontend/forms/<id>.json`: what an action sends, or the errors it shows; `(initial)` the starting inputs |
| README `## Flow scenarios`, one `### Scenario: <name>` each | `FlowScenariosTest` (engine) | the deployed process with the job executor off: start, complete tasks, expected tasks, variables and the step the case waits at |
| `service-tasks/<task>.md` `## Example` (and `## Example: <case>`) | `TemplateExamplesTest` (engine) | the payload template with the engine's beans, expectations by JSON pointer; again with a hostile suffix on every string of text, which must still give a JSON object |

Cells are JSON literals in backticks. Examples are required wherever the
section exists; flow scenarios are recommended, not required.

## Shared value rules

`cib7/src/main/resources/schemas/core-v1.json`
(`https://companylab.ai/schemas/core/v1.json`) defines `nonBlank`, `email`,
`emailOrEmpty`, `vin`, `personalCodeEE`, `contact`, `pendingUpload` and
`pendingUploadOrNull` for the value schemas to `$ref`.

## What a pack cannot do

- Run code: a pack is data. BPMN may call connectors, the beans above, Spin,
  and the core classes released for packs (`PackManifest.RELEASED_CLASSES`:
  today `com.poc.cib7.consent.ConsentPartiesListener`, the task listener that
  builds the co-signing parties). No Java delegate or expression of its own,
  no script task, no inline script, no TSX form outside the escape hatch
  (`Renderer: tsx`, which needs a core change). `PackConformanceTest` checks
  the BPMN for this.
- Name an engine bean: expressions and templates resolve only the names in
  [What templates and documents see](#what-templates-and-documents-see)
  (`BeanExposureTest`). This is not a sandbox yet: through `execution` and
  Java method calls, a BPMN expression or a payload template can still reach
  the engine's configuration and the process environment. Payload templates
  refuse `?new` and `?api`, and the pack check refuses reflection-like
  expressions (`getClass`, `forName`, `execution.processEngine…`;
  `PackIntegrationTest`), but an image built without the pack check is not
  protected: a pack is reviewed code with the engine's power.
- Send a credential, or call anything but the
  [offered destinations](#integrations-offered-to-a-pack): a connector sets no
  `X-API-Key` or `X-Internal-Token`, and the engine adds each only for its
  own destination (`PackIntegrationTest`, `OutboundCredentialsInterceptorTest`).
- Reach the core's services through its own integrations: its packages carry
  no address or secret and use `pack-` ids only (`PackIntegrationTest`), the
  deploy refuses a pack address naming a core host, and Patchbay shares no
  network with the backend, Keycloak, Postgres, RustFS or Graylog.
- Add an HTTP endpoint: registries and co-signing choose an access class
  from a closed set; everything else is core (docs/security.md rule 5).
- Touch a core table: registry migrations run in their own schema.
- Grant access: authorization is core (docs/security.md rule 1); a pack's
  BPMN names candidate groups, it creates no grants.
- Inject markup or CSS: values are escaped by format (`?json_string`,
  `.ftlh`), colours are hex, fonts are names, images are bare file names.

## Checking a pack

```bash
scripts/pack-check.sh [pack-dir]      # default: packs/reference
```

Runs every check above against the pack: the engine's and the backend's
JUnit tests tagged `pack`, the frontend's `src/pack` tests and the MCP
sidecar's `src/services/pack.test.ts`. Tests of the
reference services' own behaviour are not tagged and do not run. The core's
CI runs the full test suites, which include the same checks on the reference
pack.

## Not in the platform API yet

- Groups and roles of the pack's own: a pack's users join the core groups,
  because only those get engine grants (`AuthorizationBootstrap`).
- App icons (PNG) from the pack: the mobile app's icons are the core mark.
- Font files from the pack: a brand font must be one the page already loads.
