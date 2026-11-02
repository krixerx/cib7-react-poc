# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The **core** of eRegistrations (POC): a CIB seven 2.2 process engine that runs
BPMN e-government services, a React SPA that renders each human task, a business
backend, an integration bus, a Flutter applicant app and an MCP sidecar. Auth is
Keycloak OIDC throughout; the same deployment is also driveable over MCP.

The services themselves are not here. They live in **service packs**, one
repository per customer, which reach the core only through the platform API
(`docs/platform-api.md`) and build their images as thin layers on the core's.
The reference pack, with `vehicleRegistration` and `businessRegistration`, is
[krixerx/eregistrations-reference-pack](https://github.com/krixerx/eregistrations-reference-pack).
This repository keeps `packs/test/`, a frozen copy of it that the core's tests,
`docker compose`, `mvn spring-boot:run` and `npm run dev` run on.

Business services are **spec-first**: a pack's markdown under
`docs/business/services/<service>/` is the source of truth, and BPMN, DMN,
FreeMarker templates, form definitions and the MCP manifests are generated from
it by the `/service-builder` skill. See "Changing a service" below.

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
mvn -f cib7/pom.xml test -Dtest=DecisionExamplesTest
cd frontend && npm test -- --run src/api/bpmn.test.ts
```

End-to-end (Playwright, against the public VM by default; see `e2e/README.md`):

```bash
cd e2e && npm install && npx playwright install chromium && npm test
```

`npm run typecheck` is the cheapest correctness check after a TypeScript change.
Node 24 for `frontend` (npm 10 crashes resolving vitest 4's peers, so write
locks with npm 11), Node 24 for `mcp`, JDK 21 for both Java
modules (enforced: the poms refuse another JDK, so point `JAVA_HOME` at a JDK 21).

Package the service-builder skill the way a core release ships it (pack
repositories vendor this; fails on a link that would be dead there):

```bash
node scripts/package-service-builder.mjs --version <x.y.z> [--out dist/service-builder]
```

Check a service pack against the platform API (docs/platform-api.md):

```bash
scripts/pack-check.sh [pack-dir]     # default packs/test; runs the checks tagged `pack`
```

Regenerate a service's flow diagram after touching its BPMN:

```bash
cd scripts && npm install
node bpmn-to-mermaid.mjs ../packs/test/engine/processes/<service>/<service>.bpmn \
  --out ../packs/test/docs/business/services/<service>/README.md
# rewrites the block between the bpmn-diagram:start / :end markers in place
```

## Architecture

```
browser ──OIDC PKCE──▶ Keycloak (:8180, realm cib7-poc)
   │
   ├─ /                → frontend/ (React SPA; nginx in Docker, Vite in dev)
   ├─ /mobile          → mobile/ (Flutter web applicant app)
   ├─ /engine-rest,
   │  /camunda/*       → cib7/ (Spring Boot 4 + CIB seven 2.2 engine, Postgres)
   ├─ /api/*           → backend/ (Spring Boot 4 business microservice, Postgres)
   └─ /mcp             → mcp/ (stateless Bearer-proxy MCP sidecar, 16 tools)

cib7 engine ──all outbound HTTP──▶ esb/ (Apache Camel JBang, YAML routes)
                                     ├─▶ mailpit  (notification emails)
                                     ├─▶ pdf-renderer/ ──▶ gotenberg (HTML → PDF)
                                     └─▶ backend  (registry, documents; bus injects X-Internal-Token)

cib7 · backend · esb · mcp · pdf-renderer ──GELF 12201──▶ graylog (+ opensearch, mongodb)
```

Module responsibilities are strict and worth preserving:

- **`cib7/` is engine plus plugins only.** No business endpoints and no service
  files. It holds Keycloak identity wiring, the variable policy filter and the
  Connect http-connector config. The BPMN/DMN, variable policies and FreeMarker
  connector payloads live in a service pack's `engine/` (with the
  hand-designed PDF and email documents in its `documents/`). The core image
  (`cib7/Dockerfile`) holds no pack; the pack's layer (its
  `docker/engine.Dockerfile`) copies it to `/opt/services`, which the core
  puts on the classpath with `PropertiesLauncher` and `loader.path`. Backend,
  mcp, frontend and mobile are built the same way: a pack-less
  `<module>/Dockerfile` (compose service `<name>-core`, `scale: 0`) and a thin
  layer from the pack's `docker/`. Here compose, the tests (through the pom's
  `services.pack.dir` and `PACK_DIR`), `mvn spring-boot:run` and `npm run dev`
  use the core test pack `packs/test/`; keep it a valid pack
  (`scripts/pack-check.sh`).
- **`backend/` owns every `/api/**` surface**: public token-link pages
  (co-signing at `/api/public/consent/<purpose>` from the pack's
  `consent/<purpose>.yaml`, payments), the anonymous service list
  (`/api/public/services`; `/engine-rest` takes no anonymous call), the registry module that
  serves the pack's declared registries (`/api/{public,internal}/registry/<entity>`),
  document metadata plus RustFS S3 presigned URLs. It reaches the engine only
  through `/engine-rest` as the `cib7-business` service account.
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
  `packs/test/docs/business/services/*/build/mcp-service.json` and the aggregated
  `packs/test/docs/business/services/build/services.json`.

The SPA always calls same-origin paths (`/engine-rest/...`, `/api/...`), so there
is no CORS config on either Java service. The only cross-origin browser call is
document upload/download straight to RustFS with presigned URLs.

Both Java modules keep their state in **Postgres** (one `postgres` container, a
database and login role each, created by `postgres/init/01-databases.sh`;
volume `postgres-data`). Flyway owns both schemas: the engine's `ACT_*` tables
through `V1__CibSevenSchema`, which runs the CIB seven jar's own create
scripts, and the backend's through `db/migration/V*__*.sql`, with Hibernate
only validating. Tests and `mvn spring-boot:run` use in-memory H2 through the
same migrations. `docker compose down -v` wipes the data.

## The platform API

Core and pack meet only through the formats in `docs/platform-api.md`
(platform API `2.0`). A pack's `pack.yaml` states the platform API the
pack needs; the engine and the backend refuse an incompatible pack at
startup. A change to a pack format is a platform API change: additive is a
minor (bump `PackManifest.PLATFORM_MINOR` in both Java modules and the doc),
breaking is a major. Tests that hold for any pack are tagged `pack`;
`scripts/pack-check.sh` runs exactly those.

**Releases.** A core release is a `v<x.y.z>` tag on `main`: CI publishes the
`cib7-poc-*-core` images (plus `cib7-poc-esb` and `cib7-poc-pdf-renderer`)
as `<x.y.z>` and attaches `service-builder-<x.y.z>.tar.gz` to the GitHub
release. A pack moves to it with its `scripts/update-core.sh <x.y.z>`
(Renovate proposes the bump); its CI then runs this release's
`pack-check.sh`. A release whose image scan failed has no image for that
tag, so check Docker Hub before a pack moves to it.

**Deployment.** `deploy/` is one kit for any number of instances and holds
nothing of a pack: `.env` names the pack's images (`PACK_IMAGE_PREFIX`,
`PACK_TAG`) and the core release for the core-only images (`CORE_TAG`), and
the official Keycloak image mounts the pack's users file and branding from
the host's `pack/`. The Deploy to VM workflow takes a pack commit
(`pack_ref`), reads its core release from the pack's `docker/core.conf` and
ships the pack's data (`docs/ci-cd.md`). Smoke tests in `deploy.sh` must not
name a pack's services.

## Changing a service

Services change in their pack's repository, not here: edit the markdown spec
under `docs/business/services/<service>/` (`README.md`, `forms/*.md`,
`service-tasks/*.md`, `decisions/*.md`), run the pack's vendored
`/service-builder` skill, and commit spec and generated output as one atomic
change; the pack's CI runs this core's `scripts/pack-check.sh`, including the
specs' service examples. Never hand-edit generated files. In this repository
the same applies to `packs/test/`, which changes only when a core change needs
it to (`<pack>` below is `packs/test`).

Generated from the spec (paths in the pack):

- `<pack>/engine/processes/<service>/*.bpmn` and `*.dmn`
- `<pack>/engine/templates/<task>.json.ftl`
- `<pack>/engine/processes/<service>/variable-policy.json` and
  `schemas/<form-id>.json` (which variables a client may write, and their values)
- `<pack>/backend/registry/<entity>.yaml` and
  `db/registry/V<n>__*.sql` from `data/<entity>.md`
- `<pack>/backend/consent/<purpose>.yaml` from `consent.md`
- `<pack>/backend/payment/<service>.yaml` from the README's State fee
- `<pack>/backend/documents.json` and the `documents.*` catalog texts from the READMEs' Documents sections
- `<pack>/frontend/forms/<form-id>.json` (form definition, the default);
  `frontend/src/forms/<form-id>/` and `frontend/src/forms/registry.ts` only for
  `Renderer: tsx` forms
- `<pack>/docs/business/services/<service>/build/mcp-service.json` and `mcp-training.md`,
  plus the aggregated `<pack>/docs/business/services/build/services.json`
- the mermaid block between the `bpmn-diagram:start` / `bpmn-diagram:end` markers
  in the service README

If a spec is missing a required field, stop and ask rather than guessing.

## Security rules (mandatory)

`docs/security.md` holds the full rules and the list of accepted demo
exemptions (seeded users and passwords, dev-default secrets, self-signed TLS).
Read it before touching auth, endpoints, variables, links, integrations or
containers. The short form:

- **No wildcard engine grants for applicants.** Grants only add access; there
  is no implicit "own tasks" filter. Applicants reach their own case through
  per-instance grants for the initiator and per-task assignee/candidate grants.
- **Process variables from clients are untrusted.** A form writes only the
  variables in its generated `variable-policy.json`, and only values its
  generated `schemas/<form-id>.json` accepts (shared rules in
  `cib7/src/main/resources/schemas/core-v1.json`); system-owned variables
  (decisions, payment, consent, identity) are set server-side only, and
  config beans resolve before variables.
- **Capability links are minted server-side**, bound to case, party, round and
  expiry, never stored raw where another party can read them, never returned
  to other parties.
- **External facts need proof.** Payment counts only on a signed provider
  callback, never on a browser call.
- **Every `/api` path is in one class:** `/api/public/**` (capability or
  harmless reference data), `/api/internal/**` (X-Internal-Token, never
  routed by the ingress), everything else JWT. Unmatched paths are denied.
- **Check ownership of every id or key** a caller passes.
- **Encode at every boundary:** `?json_string`, `?html`, encoded URL segments.
- **Ingress sends security headers and rate-limits public paths.** Containers
  run as non-root on pinned images; internal services share a network only
  when they must talk.
- **Every security fix lands with a negative test.**

## Contracts and gotchas that bite

Lessons that apply to one area live in its skill in `.claude/skills/`, each with a rule table and the full story behind every rule: `backend-conventions` (B), `frontend-conventions` (F), `deployment-conventions` (D), `process-artifacts` (P), `esb-conventions` (E), `mcp-conventions` (MCP), `mobile-conventions` (MOB) and `pdf-renderer-conventions` (PDF). A new lesson goes into the skill with one line here, so this file stays small: it is loaded into every session and every agent. `.claude/rules/` points each module at its skill when its files are touched.

- A wrong form id shows up only on the task page, as "No form definition found". See `process-artifacts` P1.
- One engine deployment per service folder; its DMNs live there too. See `process-artifacts` P2.
- Group ids have no leading slash (`civil-servant`). See `process-artifacts` P3.
- A payload template cannot `<#include>`; call the `documents` bean. See `process-artifacts` P4.
- DMN files declare `historyTimeToLive`, and the namespace is `camunda:`. See `process-artifacts` P5, P6.
- Variables over ~4 kB must be `byte[]`. See `backend-conventions` B11.
- A CIB seven upgrade or an entity change needs a Flyway migration. See `backend-conventions` B10.
- The `graylog` profile turns GELF on; `MdcUserFilter` stays at order `-99`. See `backend-conventions` B21.
- The realm's `frontendUrl` outranks every `KC_HOSTNAME*` setting, and the v1 hostname options are ignored. See `deployment-conventions` D9.
- The realm is two files imported once; `deploy/keycloak/` copies stay identical. See `deployment-conventions` D10.
- The realm export holds only placeholders, written `${VAR}`, never `${env.VAR}`. See `deployment-conventions` D8.
- nginx upstreams are variables, and `index.html` is never cached. See `deployment-conventions` D1, D2.
- Service texts live in the pack, core texts in the SPA, in `en` and `ar`. See `frontend-conventions` F6.
- Styling is plain CSS on tokens, with no component library. See `frontend-conventions` F4.
- **Cockpit is the debugger.** Connector, DMN and FreeMarker failures surface as
  engine incidents at `/camunda/app/cockpit/`; an incident usually means the spec
  did not cover a case.

## Team

The roles are subagents in `.claude/agents/`, built in the teambuilder format; `_team.md` lists them and `_stack.md`, `_standards.md` and `_testing.md` are the shared context they read. `.claude/skills/` holds the rules, and each persona preloads only the ones it needs through `skills:` in its frontmatter. Sonnet runs the roles that work inside strong rule tables (designer, frontend, tester); Opus runs the rest.

## Spec-first workflow (OpenSpec)

Services are specified in their pack (see "Changing a service"). Non-trivial **core** work goes through an OpenSpec change: propose, specs, apply, archive, driven by `/opsx:*` (`.claude/commands/opsx/`). `openspec/specs/` records current behaviour as changes archive into it, and starts empty. Route to personas:

- `/opsx:explore` → `analyst`
- `/opsx:propose` → `architect`
- `/opsx:apply` → infer from pending tasks: design → `designer`; service spec or generated artifacts → `process-modeller`; implementation → `programmer-<variant>`; testing → `tester`
- Before commit → `reviewer` or `/review`

## Agent handoffs

Agents do not talk to each other: the session that runs them carries what one leaves for the next, and whatever exists only in a final report is gone when that session ends. So a handoff is written down.

- **Anything another agent needs goes in `openspec/changes/<change>/handoffs.md`**: a bus route specification, the scenarios a test must cover, a question for the analyst. The first agent with something to hand over creates the file. With no OpenSpec change, it goes in the final report under *For others*.
- **One entry per handoff, newest last:**

  ```markdown
  ## <short title>

  - From: process-modeller
  - To: programmer-backend
  - Status: open
  - Task: 2.3 in tasks.md

  What the other agent needs, complete enough to act on without this conversation.
  ```

- **Before starting, an agent reads the open entries addressed to it.** When it has finished one it sets `Status: done`; a question it cannot answer stays open with a line saying why. Nobody deletes an entry. A change with an open entry is not ready for `/opsx:archive`.
- **Every agent ends its run with this report:**

  ```markdown
  Done: what was finished, by task number where there is one
  Changed: files
  Verified: commands run and their result, or "not verified" and why
  For others: handoff entries written, or the handoff itself with no change
  Blocked: open questions, or "nothing"
  ```

## Review rules

The rules a change is checked against live in the conventions skills in
`.claude/skills/`, one per module. Each is a table of rule IDs and severities; where a rule is already written
down here or in `docs/security.md` the table points there, and the rest carry
their reasoning in the skill. `/review` checks staged changes against
them and ends with BLOCKED, ALLOWED WITH WARNINGS or PASSED.

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
