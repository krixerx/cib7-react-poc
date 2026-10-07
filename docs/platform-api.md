# Platform API

**Platform API version:** `2.0`

`2.0` is not released yet: until a core release ships it to a pack other than
the reference pack, it may still change without a major bump. Changes since
it was first written: `links.consent(execution, purpose, partyId)` replaced
`links.owner` / `links.founder`, policies gained `identity`, packs gained
`backend/payment/` and `backend/documents.json`, the two consent pages
became one, `/consent/<purpose>/<token>`, worded by the pack, and the MCP
manifests became format 2: texts and offered fields only, with the value rules
taken from the form schemas.

**When to read this:** before writing or changing anything in a service pack,
before changing core code that reads pack files, and before a core release.
This is the contract between the core (this repository's modules) and a
service pack (`packs/reference/` today, one repository per customer later).
The core reads a pack only through the formats below; a pack reaches the core
only through them.

Contents: [Versioning](#versioning) · [Pack layout](#pack-layout) ·
[Formats](#formats) · [What templates and documents see](#what-templates-and-documents-see) ·
[Brand tokens](#brand-tokens) · [Shared value rules](#shared-value-rules) ·
[What a pack cannot do](#what-a-pack-cannot-do) · [Checking a pack](#checking-a-pack) ·
[Not in the platform API yet](#not-in-the-platform-api-yet)

## Versioning

The platform API has a `major.minor` version, stated above and in
`PackManifest.PLATFORM_MAJOR` / `PLATFORM_MINOR` in the engine and the backend
(a test holds all three equal).

- **A minor only adds:** an optional key, a new field type, a new bean, a new
  token. A pack built for `2.0` runs on every `2.x`.
- **A major may break:** a key renamed or removed, a meaning changed. Packs
  move to it with the core release notes.
- **The service-builder writes for one platform API.** Each core release
  ships it as `service-builder-<x.y.z>.tar.gz` on its GitHub release
  (`release.yml`); a pack repository vendors the copy for the core version it
  runs on, and its `VERSION` file names both.
- **The pack states what it needs** in `pack.yaml` (`platform: "2.0"`). The
  engine (before deploying anything) and the backend (before reading any
  descriptor) refuse to start with another major or a newer minor, and say
  which core release fits.
- **Formats also carry their own version** where they are read by the SPA,
  which cannot read `pack.yaml`: form definitions, the catalog, `brand.json`
  and `tokens.json` say `"version": 1` and are refused whole at another
  value. Backend descriptors say `platform: 2`, the major they are written
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
├── docker/core.conf                   the core release the pack runs on (CORE_VERSION; Renovate bumps it)
├── docker/<image>.Dockerfile         the pack's images: each FROM a core image, adds the pack's files
│                                     (frontend, engine, backend, mcp, mobile); a pack with
│                                     ESB routes adds esb.Dockerfile (FROM the bus image,
│                                     COPY esb/routes/ to /routes/). Keycloak runs the stock
│                                     image: the core realm and theme and the pack's users
│                                     file and branding are mounted
└── branding/                         served at /pack/branding/, engine classpath branding/
    ├── brand.json, tokens.json, logo and favicon images
    └── locales/<lang>/brand.json
```

Beside these, a pack repository carries its tooling (inert while the pack
sits in the core repository): `.claude/skills/service-builder/` (vendored by
`scripts/update-core.sh <x.y.z>` from that core release),
`.github/workflows/check.yml` (the core's `pack-check.sh` at `CORE_VERSION`,
and the vendored skill from the same release), `.github/workflows/publish.yml`
(the layers on the core images, Trivy, push), `renovate.json`, `README.md` and
`CLAUDE.md`. `packs/reference/` holds the template for all of them.

The pack's specs, the source of everything generated in it, live in its
`docs/business/services/<service>/`, together with the generated MCP
manifests (`<service>/build/`, `build/services.json`). Its own ESB routes,
when it has integrations of its own, live in `esb/routes/pack-<name>.yaml`
(the reference pack has none).

## Formats

| Path in the pack | Format | Read by | Checked by |
|---|---|---|---|
| `pack.yaml` | YAML: `name` (kebab), `version` (`x.y.z`), `platform` (`"M.m"`) | `PackManifest` (engine, backend) | `PackManifestTest` |
| `engine/processes/<service>/*.bpmn` | BPMN 2.0, `camunda:` namespace; user tasks `camunda:formKey="react:<form-id>"`; `candidateGroups` without a leading slash; connectors only to the [bus paths](#what-templates-and-documents-see) offered to a pack; business rule tasks `decisionRefBinding="deployment"` | `ServiceDeployments` | `PackConformanceTest`, `PackBusTest`, `ServiceSpecsTest` |
| `engine/processes/<service>/*.dmn` | DMN 1.3 with `camunda:historyTimeToLive` | the engine, in the service's deployment | `PackConformanceTest` |
| `engine/processes/<service>/variable-policy.json` | `processDefinitionKey`, `start` and `forms.<form-id>`: the variables a client may write; `identity`: variable to `givenName`, `familyName` or `email`, set from the account and re-checked on completion | `VariablePolicyRegistry`, `VariableWritePolicyFilter`, `IdentityFieldRegistry` | `VariablePolicyFilesTest`, `IdentityFieldRegistryTest` |
| `engine/processes/<service>/schemas/*.json` | JSON Schema 2020-12 with `x-process` and `x-form`; may `$ref` the [shared value rules](#shared-value-rules) | `FormSchemaRegistry` | `FormSchemaRegistryTest` |
| `engine/templates/*.json.ftl` | FreeMarker producing JSON; every string `?json_string`; no `<#include>` | the engine's FreeMarker script engine (BPMN `resource=`) | `PackConformanceTest`, `ServiceSpecsTest` |
| `engine/documents/` | `pdf/<name>.ftlh` (HTML, escaped), `email/<name>.ftl` (text), shared `_brand.ftlh` | `DocumentRenderer` (`documents` bean) | `PackConformanceTest` |
| `backend/registry/<entity>.yaml` | registry descriptor, `platform: 2`: entity, table, key, sort, typed fields, derived fields, operations with access `public` or `internal` | `RegistryCatalog`, `RegistryController` | `BackendApplicationSmokeTest` |
| `backend/db/registry/V<n>__*.sql` | Flyway migrations in the `registry` schema, own history table | `RegistryMigrations` | `BackendApplicationSmokeTest` (columns against descriptors) |
| `backend/consent/<purpose>.yaml` | co-signing descriptor, `platform: 2`: process, variables, messages, wording | `ConsentCatalog`, `ConsentController` | `ConsentCatalogTest`; `ConsentTextsTest` (the page texts in `frontend/locales/<lang>/consent.json` for every purpose, detail and document) |
| `backend/payment/<service>.yaml` | state fee, `platform: 2`: process, fee name, recipient, currency, `amount` flat or tiered by one engine-set variable | `FeeCatalog`, `FeeSchedule` (checkout, callback, `/api/internal/payments/quote/<id>`) | `FeeCatalogTest`; `PackConformanceTest` (tier variable not client-writable) |
| `backend/documents.json` | document categories, `platform: 2`: `by: applicant` (a signed-in user may upload) or `by: system` (only the engine files it; name starts `generated-`); the core adds `generated-certificate` | `DocumentCategories` (user endpoints accept only applicant categories, `server-upload` only system ones) | `DocumentCategoriesTest`; `PackConformanceTest` and `src/pack/pack.test.ts` (every category used is declared and labelled) |
| `keycloak/<realm>-users-0.json` | Keycloak users file (`realm`, `users`), imported after the core realm `keycloak/cib7-poc-realm.json` from the same directory; users join core groups only (`/applicant`, `/civil-servant`, `/cib7-admin`) and get their roles from them | Keycloak (`--import-realm`) | `RealmFilesTest` |
| `frontend/catalog.json` | catalog v1: namespaces, services (category, issuer), issuers (tone) | `src/pack/catalog.ts` | `src/pack/pack.test.ts` |
| `frontend/forms/<form-id>.json` | form definition v1 | `src/forms/schema/definition.ts` | `src/pack/pack.test.ts` |
| `frontend/locales/<lang>/<ns>.json` | i18next JSON; `catalog`, `names` and one namespace per form; `en` and `ar` with the same keys | `loadPack()`; the mobile app reads `catalog` (document labels) | `src/pack/pack.test.ts` (also: every BPMN `name=` translated) |
| `branding/brand.json` | brand v1: `logo.light`, `logo.dark`, `favicon` (bare image file names) | `src/pack/brand.ts`, `DocumentBrand`, the login theme (`keycloak/themes/cib7/login/template.ftl`) | `src/pack/brand.test.ts` |
| `branding/tokens.json` | brand v1: [brand tokens](#brand-tokens) per scheme (hex), `fonts.display` / `fonts.body` (family names) | `src/pack/brand.ts`, `DocumentBrand`, the login theme, the mobile app (`mobile/lib/pack.dart`) | `src/pack/brand.test.ts` (with WCAG AA contrast) |
| `branding/locales/<lang>/brand.json` | `name`, `sub`, `portal` | `loadBrand()`, `DocumentBrand`, the login theme, the mobile app (`mobile/lib/pack.dart`) | `src/pack/brand.test.ts` |
| `esb/routes/pack-<name>.yaml` | Camel YAML DSL, route entries only, ids `pack-*`: listen on `platform-http:/pack/<name>` with `direct:bus-auth` as the first step, or on `direct:pack-*`; send only to `http(s)` systems outside the stack (a literal host or `${env.PACK_*}`), `direct:pack-*` or `log:`; read only `PACK_*` environment variables; never touch `X-Internal-Token` or `X-Bus-Token`; no code (`bean`, scripts, `groovy`, ...) | the ESB (`camel run --source-dir=/routes`) | `PackBusTest` |
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
| `busBaseUrl` | BPMN, templates | the bus; the only address for outbound calls |
| `frontendBaseUrl` | BPMN, templates, documents | the portal's public URL, for links in emails |
| `links` | templates, documents | mints capability tokens: `consent(execution, purpose, partyId)` for a purpose in `backend/consent/`, `payment(execution)` |
| `pdf` | BPMN, templates | `decode(base64)` to `byte[]`, `encode(byte[])` to base64 |
| `documents` | templates | `html("<name>", execution)`, `text("<name>", execution)` |
| `brand` | documents | `name`, `primary`, `logo` (data URI or null) |
| `S(…)` | BPMN, templates | Spin JSON |

These names win over case variables of the same name
(`ReservedBeansPlugin.RESERVED_NAMES`), so a client cannot redirect a
connector or a link by writing a variable.

Bus paths a pack may call: `/api/v1/send` (mail), `/render` (PDF),
`/api/internal/documents/move-pending` and `/server-upload` (case
documents), `/api/{public,internal}/registry/<entity>` (its registries),
`/api/internal/payments/quote/<processInstanceId>` (the case's state fee, as
`amount` and `currency`), and `/pack/<name>/...`, served by its own routes.
A connector's `url` is a plain `${busBaseUrl}<path>` value, with no query,
fragment or `..`; a registry path names a registry the pack declares, in an
access class one of its operations has (`PackBusTest`).

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
with the pack and holds on every core release it runs on:

| In the spec | Run by | Against |
|---|---|---|
| `decisions/<id>.md` `## Examples` (and `## Examples without \`<rule id>\``) | `DecisionExamplesTest` (engine) | the pack's DMN, each row matching exactly one rule |
| README `### Fee examples` under `## State fee` | `FeeExamplesTest` (backend) | the backend's `FeeSchedule` on `payment/<service>.yaml`, plus the section's recipient and currency |
| `data/<entity>.md` `## Seed` | `RegistrySeedTest` (backend) | every row read back through the registry endpoint class each operation declares |

Cells are JSON literals in backticks. Examples are required wherever the
section exists.

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
- Reach the core's secrets or services from its bus routes: the ESB holds
  the token that opens the backend's internal API, so a pack route can
  neither set it nor call a core host (`PackBusTest`).
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
