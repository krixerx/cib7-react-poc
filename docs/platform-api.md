# Platform API

**Platform API version:** `2.0`

`2.0` is not released yet: until a core release ships it to a pack other than
the reference pack, it may still change without a major bump. Changes since
it was first written: `links.consent(execution, purpose, partyId)` replaced
`links.owner` / `links.founder`, policies gained `identity`, packs gained
`backend/payment/` and `backend/documents.json`.

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
├── frontend/                         served at /pack/
│   ├── catalog.json
│   ├── forms/<form-id>.json
│   └── locales/<lang>/<namespace>.json
└── branding/                         served at /pack/branding/, engine classpath branding/
    ├── brand.json, tokens.json, logo and favicon images
    └── locales/<lang>/brand.json
```

Until the pack has its own repository, its specs live in this repository's
`docs/business/services/<service>/` and its ESB routes in `esb/routes/`
(file names and route ids `pack-*`).

## Formats

| Path in the pack | Format | Read by | Checked by |
|---|---|---|---|
| `pack.yaml` | YAML: `name` (kebab), `version` (`x.y.z`), `platform` (`"M.m"`) | `PackManifest` (engine, backend) | `PackManifestTest` |
| `engine/processes/<service>/*.bpmn` | BPMN 2.0, `camunda:` namespace; user tasks `camunda:formKey="react:<form-id>"`; `candidateGroups` without a leading slash; connectors only to `${busBaseUrl}`; business rule tasks `decisionRefBinding="deployment"` | `ServiceDeployments` | `PackConformanceTest`, `ServiceSpecsTest` |
| `engine/processes/<service>/*.dmn` | DMN 1.3 with `camunda:historyTimeToLive` | the engine, in the service's deployment | `PackConformanceTest` |
| `engine/processes/<service>/variable-policy.json` | `processDefinitionKey`, `start` and `forms.<form-id>`: the variables a client may write; `identity`: variable to `givenName`, `familyName` or `email`, set from the account and re-checked on completion | `VariablePolicyRegistry`, `VariableWritePolicyFilter`, `IdentityFieldRegistry` | `VariablePolicyFilesTest`, `IdentityFieldRegistryTest` |
| `engine/processes/<service>/schemas/*.json` | JSON Schema 2020-12 with `x-process` and `x-form`; may `$ref` the [shared value rules](#shared-value-rules) | `FormSchemaRegistry` | `FormSchemaRegistryTest` |
| `engine/templates/*.json.ftl` | FreeMarker producing JSON; every string `?json_string`; no `<#include>` | the engine's FreeMarker script engine (BPMN `resource=`) | `PackConformanceTest`, `ServiceSpecsTest` |
| `engine/documents/` | `pdf/<name>.ftlh` (HTML, escaped), `email/<name>.ftl` (text), shared `_brand.ftlh` | `DocumentRenderer` (`documents` bean) | `PackConformanceTest` |
| `backend/registry/<entity>.yaml` | registry descriptor, `platform: 2`: entity, table, key, sort, typed fields, derived fields, operations with access `public` or `internal` | `RegistryCatalog`, `RegistryController` | `BackendApplicationSmokeTest` |
| `backend/db/registry/V<n>__*.sql` | Flyway migrations in the `registry` schema, own history table | `RegistryMigrations` | `BackendApplicationSmokeTest` (columns against descriptors) |
| `backend/consent/<purpose>.yaml` | co-signing descriptor, `platform: 2`: process, variables, messages, wording | `ConsentCatalog`, `ConsentController` | `BackendApplicationSmokeTest` |
| `backend/payment/<service>.yaml` | state fee, `platform: 2`: process, fee name, recipient, currency, `amount` flat or tiered by one engine-set variable | `FeeCatalog`, `FeeSchedule` (checkout, callback, `/api/internal/payments/quote/<id>`) | `FeeCatalogTest`; `PackConformanceTest` (tier variable not client-writable) |
| `backend/documents.json` | document categories, `platform: 2`: `by: applicant` (a signed-in user may upload) or `by: system` (only the engine files it; name starts `generated-`); the core adds `generated-certificate` | `DocumentCategories` (user endpoints accept only applicant categories, `server-upload` only system ones) | `DocumentCategoriesTest`; `PackConformanceTest` and `src/pack/pack.test.ts` (every category used is declared and labelled) |
| `frontend/catalog.json` | catalog v1: namespaces, services (category, issuer), issuers (tone) | `src/pack/catalog.ts` | `src/pack/pack.test.ts` |
| `frontend/forms/<form-id>.json` | form definition v1 | `src/forms/schema/definition.ts` | `src/pack/pack.test.ts` |
| `frontend/locales/<lang>/<ns>.json` | i18next JSON; `catalog`, `names` and one namespace per form; `en` and `ar` with the same keys | `loadPack()` | `src/pack/pack.test.ts` (also: every BPMN `name=` translated) |
| `branding/brand.json` | brand v1: `logo.light`, `logo.dark`, `favicon` (bare image file names) | `src/pack/brand.ts`, `DocumentBrand` | `src/pack/brand.test.ts` |
| `branding/tokens.json` | brand v1: [brand tokens](#brand-tokens) per scheme (hex), `fonts.display` / `fonts.body` (family names) | `src/pack/brand.ts`, `DocumentBrand` | `src/pack/brand.test.ts` (with WCAG AA contrast) |
| `branding/locales/<lang>/brand.json` | `name`, `sub`, `portal` | `loadBrand()`, `DocumentBrand` | `src/pack/brand.test.ts` |

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
`amount` and `currency`), and the paths of its own `pack-*` routes.

## Brand tokens

`tokens.json` may set, per colour scheme: `primary`, `primary-hover`,
`primary-ink`, `primary-soft`, `primary-soft-border`, `mesh-1` to `mesh-4`,
`banner-bg`, `banner-fg`, `banner-strong`; and the fonts `display` and
`body`. Every other token in `frontend/src/styles/tokens.css` is core. The
list lives in `BRAND_COLOR_TOKENS` / `BRAND_FONT_TOKENS`
(`frontend/src/pack/brand.ts`); adding a name is a minor change, removing one
a major.

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
JUnit tests tagged `pack`, and the frontend's `src/pack` tests. Tests of the
reference services' own behaviour are not tagged and do not run. The core's
CI runs the full test suites, which include the same checks on the reference
pack.

## Not in the platform API yet

- Keycloak realm overlay and login theme (plan tasks S30, S31): the realm is
  core today.
- Mobile brand and document labels (S27 to S29).
- Font files from the pack: a brand font must be one the page already loads.
- MCP manifests: read from `docs/business/services/*/build/`, which moves
  into the pack with the specs.
