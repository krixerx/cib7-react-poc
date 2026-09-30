# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A monorepo POC ("eRegistrations") where a CIB seven 2.2 process engine runs BPMN
e-government services and a React SPA renders each human task with a hand-written
form. Four services ship: `vehicleRegistration`, `businessRegistration`,
`transportVehicleRegistration`, `transportLearningPermit`. Auth is Keycloak OIDC
throughout; the same deployment is also driveable over MCP.

The deepest thing to internalize: **business services are spec-first**. The
markdown under `docs/business/services/<service>/` is the source of truth, and
BPMN, DMN, FreeMarker templates, React forms, the form registry and the MCP
manifests are generated from it. See "Changing a service" below.

## Commands

Full stack (the normal way to test anything end to end):

```bash
docker compose up --build            # SPA + ingress on http://localhost:3000
docker compose --profile dev up -d mailpit-ui    # Mailpit inbox on :8025 (opt-in)
docker compose --profile traefik up -d traefik   # Traefik ingress (off by default)
docker compose logs -f esb           # watch every outbound integration cross the bus
```

Logins: `bart`/`bart` (applicant, PartA), `homer`/`homer` (civil servant, PartB),
`admin`/`admin` (Camunda webapps under `/camunda/*`). Keycloak stays on `:8180`.

Per-module dev loops:

```bash
cd cib7 && mvn spring-boot:run        # engine on :8080 (auto-deploys BPMN/DMN)
cd backend && mvn spring-boot:run     # business API on :8085
cd frontend && npm install && npm run dev   # Vite on :5173, proxies /engine-rest and /api
cd mcp && npm run dev                 # MCP sidecar
```

Quality gates (same as `.github/workflows/quality.yml`; run from the repo root so
`.mvn/jvm.config` applies, google-java-format needs its `--add-exports` flags):

```bash
mvn -B -f cib7/pom.xml verify         # spotless:check + tests
mvn -B -f backend/pom.xml verify
mvn -f cib7/pom.xml spotless:apply    # reformat to Google Java Style
cd frontend && npm run format:check && npm run lint && npm run typecheck && npm test -- --run && npm run build
cd mcp && npm run format:check && npm run lint && npm run typecheck && npm test -- --run
```

Single tests:

```bash
mvn -f cib7/pom.xml test -Dtest=DmnEvaluationTest
cd frontend && npm test -- --run src/api/bpmn.test.ts
```

`npm run typecheck` is the cheapest correctness check after a TypeScript change.
Node 22 for `frontend` (npm 10.9 hoists TEDI's deps differently from npm 10.8, so
a lock written on Node 20 fails `npm ci`), Node 20 for `mcp`, JDK 21 for both Java
modules.

Regenerate a service's flow diagram after touching its BPMN:

```bash
cd scripts && npm install
node bpmn-to-mermaid.mjs ../cib7/src/main/resources/processes/<service>/<service>.bpmn \
  --out ../docs/business/services/<service>/README.md
# rewrites the block between the bpmn-diagram:start / :end markers in place
```

## Architecture

```
browser ──OIDC PKCE──▶ Keycloak (:8180, realm cib7-poc)
   │
   ├─ /                → frontend/ (React SPA; nginx in Docker, Vite in dev)
   ├─ /mobile          → mobile/ (Flutter web applicant app)
   ├─ /engine-rest,
   │  /camunda/*       → cib7/ (Spring Boot 3.5 + CIB seven 2.2 engine, in-memory H2)
   ├─ /api/*           → backend/ (Spring Boot 4 business microservice, in-memory H2)
   └─ /mcp             → mcp/ (stateless Bearer-proxy MCP sidecar, 11 tools)

cib7 engine ──all outbound HTTP──▶ esb/ (Apache Camel JBang, YAML routes)
                                     ├─▶ mailpit  (notification emails)
                                     ├─▶ pdf-renderer/ ──▶ gotenberg (HTML → PDF)
                                     └─▶ backend  (registry, documents; bus injects X-Internal-Token)

cib7 · backend · esb · mcp · pdf-renderer ──GELF 12201──▶ graylog (+ opensearch, mongodb)
```

Module responsibilities are strict and worth preserving:

- **`cib7/` is engine plus plugins only.** No business endpoints. It holds the
  BPMN/DMN, FreeMarker connector payloads, Keycloak identity wiring and the
  Connect http-connector config.
- **`backend/` owns every `/api/**` surface**: public token-link pages (owner
  confirmations, founder signatures, payments), the curated vehicle registry,
  document metadata plus RustFS S3 presigned URLs, the transport registries. It
  reaches the engine only through `/engine-rest` as the `cib7-business` service
  account.
- **`esb/` is the only address the engine knows for outbound calls.**
  `BusConfiguration.java` exposes `${busBaseUrl}` (`http://esb:8080`) to BPMN;
  per-system addresses and the internal token live in `esb/routes/*.yaml`. A new
  integration is a new YAML route file, not a new config class in the engine.
- **Logs go to Graylog, not just stdout.** All five services we write ship
  structured GELF to `graylog:12201`; see `docs/logging.md`. Graylog is bound to
  `127.0.0.1:9900` on the host (SSH tunnel to reach it on a server) and its GELF
  inputs are unpublished. The field contract is `service`, `level` +
  `level_name`, and `user_id` where a Keycloak user is attributable.
- **`mcp/` forwards the caller's own Bearer** to `/engine-rest`. There is no AI
  service account, so everything an agent does is attributable to a real
  Keycloak user. Its per-service schemas come from the generated
  `docs/business/services/*/build/mcp-service.json` and the aggregated
  `docs/business/services/build/services.json`.

The SPA always calls same-origin paths (`/engine-rest/...`, `/api/...`), so there
is no CORS config on either Java service. The only cross-origin browser call is
document upload/download straight to RustFS with presigned URLs.

Both Java modules run **in-memory H2**: process state and document metadata are
wiped together on restart. `TODOS.md` T1 tracks the Postgres swap.

## Changing a service

Never hand-edit generated files. The loop is: edit the markdown spec under
`docs/business/services/<service>/` (`README.md`, `forms/*.md`,
`service-tasks/*.md`, `decisions/*.md`), run the `/service-builder` skill
(`.claude/skills/service-builder/SKILL.md`), test with `docker compose up --build`,
then commit spec and generated output as one atomic change.

Generated from the spec:

- `cib7/src/main/resources/processes/<service>/*.bpmn` and `*.dmn`
- `cib7/src/main/resources/templates/<task>.json.ftl`
- `frontend/src/forms/<form-id>/` and `frontend/src/forms/registry.ts` (full rewrite)
- `docs/business/services/<service>/build/mcp-service.json` and `mcp-training.md`,
  plus the aggregated `docs/business/services/build/services.json`
- the mermaid block between the `bpmn-diagram:start` / `bpmn-diagram:end` markers
  in the service README

If a spec is missing a required field, stop and ask rather than guessing.

## Contracts and gotchas that bite

- **`formKey` to registry.** A user task carries
  `camunda:formKey="react:<form-id>"`; the SPA strips `react:` and looks the id up
  in `frontend/src/forms/registry.ts`. Nothing validates this at deploy time, so a
  wrong id only shows up as a runtime error on the task page.
- **One engine deployment per `processes/<service>/` folder.**
  `ServiceDeployments.java` scans `classpath*:processes/*/` in `@PostConstruct`
  with duplicate filtering. A service's DMNs must live in the same folder because
  business rule tasks use `camunda:decisionRefBinding="deployment"`.
- **Group ids have no leading slash.** The Keycloak group path is
  `/civil-servant`, but the cibseven-keycloak plugin maps it to the engine group
  id `civil-servant`. `candidateGroups` and authorization grants must use the
  slash-less form; the realm export keeps the canonical paths.
- **Variables over ~4 kB must be `byte[]`.** H2 stores String/Text variables in a
  `VARCHAR(4000)` column, so a large String fails during the history flush. PDFs
  and similar payloads are decoded to `byte[]` with the `pdf` helper bean
  (`PdfHelper.java`) so they spill to `ACT_GE_BYTEARRAY`, and re-encoded to base64
  in the FreeMarker payload at send time.
- **The realm's `frontendUrl` outranks every `KC_HOSTNAME*` setting.** The
  `attributes.frontendUrl` in `keycloak/realm-export.json` decides every URL
  Keycloak writes into a login page for the `cib7-poc` realm, so it carries the
  `${PUBLIC_KEYCLOAK_URL}` placeholder and must never be hardcoded: pinned to
  localhost it produced a login form posting to `http://localhost:8180` on a
  public deployment whose containers were all configured correctly, with nothing
  in any log to say so. Related: Keycloak 26 **ignores** the v1 hostname options
  (`KC_HOSTNAME_URL`, `KC_HOSTNAME_STRICT_HTTPS`) after logging `Hostname v1
  options ... are still in use`, so the setting is `KC_HOSTNAME`, and the old
  spelling looks correct in `docker inspect` while configuring nothing. Both are
  import-time, so changing either needs Keycloak recreated, not restarted.
- **DMN files must declare `historyTimeToLive`** (CIB seven 2.2 hard rule).
- **Namespace is `camunda:`, not `cib:`.** CIB seven 2.2 keeps the Camunda 7
  namespace.
- **New form means new locale files.** `frontend/src/i18n/index.ts` globs
  `locales/<lang>/<namespace>.json`; the file name is the i18next namespace and a
  component opts in with `useTranslation('<namespace>')`. Supported languages are
  `en` and `ar` (RTL), so a translated screen needs both files.
- **TEDI first, MUI for complex data components.** Import TEDI from
  `@tedi-design-system/react/tedi`. MUI is pinned to v5 on purpose because TEDI
  bundles MUI v5 internally and two Emotion trees break styling.
  `TransportPermitApplicationForm.tsx` is the TEDI reference form,
  `IncidentsPage.tsx` the MUI DataGrid reference. TEDI's `LabelProvider` is pinned
  to `en` since its internal microcopy has no Arabic.
- **The `graylog` Spring profile is what turns GELF on.** `cib7` and `backend`
  define their GELF appender inside `<springProfile name="graylog">` in
  `logback-spring.xml`, and docker-compose sets `SPRING_PROFILES_ACTIVE=graylog`.
  A bare `mvn spring-boot:run` logs to the console only, on purpose — the
  `graylog` hostname does not resolve outside the compose network. Adding a
  second profile to a service means listing `graylog` alongside it.
- **`user_id` in logs comes from the MDC, set after Spring Security.**
  `MdcUserFilter` is registered at servlet order `-99`, one step after Spring
  Security's chain at `-100`. Move it earlier and it silently sees an empty
  `SecurityContext` and logs no user at all.
- **Cockpit is the debugger.** Connector, DMN and FreeMarker failures surface as
  engine incidents at `/camunda/app/cockpit/`; an incident usually means the spec
  did not cover a case.

## Conventions

- Java follows the Google Java Style Guide (enforced by Spotless during `verify`);
  TypeScript and React follow the Google TypeScript Style Guide. Match surrounding
  code.
- Java code lives under `com.poc.cib7` and `com.poc.backend`. Prefer
  `application.yaml` over a `@Bean`; the Connect plugin is the canonical exception.
- Function components only, local state with hooks, no state library. Errors render
  as text (`<p className="form-error">`), no toasts or modals.
- Class-level Javadoc and JSDoc on exported functions explain *why*; do not add
  comments restating what the code does.
- Update the doc in the same change as the code it describes. `docs/README.md` maps
  which doc answers what: `architecture.md` (topology, request flow, ports),
  `cib7.md` (engine, BPMN, connector, Keycloak), `frontend.md` (pages, forms, REST
  client, auth), `deployment.md`, `mcp.md`.
