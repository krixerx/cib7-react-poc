# Proposal

## Why

The integration bus (`esb/`, Camel JBang YAML routes) forwards the engine's calls verbatim, so every service-task template is written in the downstream system's own schema (Mailpit's `From`/`To`/`Subject`/`Text`, the PDF renderer's request) and a change of mail or PDF provider means regenerating every pack. Patchbay, our own integration product (`krixerx/patchbay`), does exactly the missing part: the caller sends its own model, and the mapping to the external system, its base URL and its secrets live in Patchbay, configured per environment. Replacing the bus with it gives packs a neutral, stable integration contract and gives operators a console to see and test each external call.

## What Changes

- **BREAKING (platform API 3.0):** the `busBaseUrl` bean is removed. BPMN connectors call either `${patchbayBaseUrl}/api/v1/integrations/<slug>` for an external system (mail, PDF, a pack's own integrations) or `${backendBaseUrl}/api/...` for the backend paths the platform API offers today. `PackManifest.PLATFORM_MAJOR` becomes 3 in both Java modules, `docs/platform-api.md` says `3.0`, `packs/reference/pack.yaml` says `platform: "3.0"` and every backend descriptor says `platform: 3`.
- **BREAKING:** mail and PDF payloads change from the downstream schemas to two core payload definitions, `mail-send` and `pdf-render` (v1), documented in the platform API. Patchbay maps them to Mailpit's `/api/v1/send` and the pdf-renderer's `/render`.
- **BREAKING:** a pack's own integrations are Patchbay packages under `<pack>/patchbay/<package>/` (unpacked, reviewable JSON) instead of `esb/routes/pack-<name>.yaml`. The `/pack/<name>/...` bus paths go away.
- Pack BPMN expressions can no longer name arbitrary Spring beans: the engine exposes only the reserved beans, payload templates refuse FreeMarker's `?new` and `?api`, and the pack check refuses reflection-like expressions. A pack remains reviewed code with the engine's power: an image built without the pack check could still reach engine configuration through reflection (design D17).
- The engine calls the backend directly. It attaches `X-Internal-Token` itself (from `INTERNAL_TASK_TOKEN`) only to requests to the backend's `/api/internal/**`, and `X-API-Key` (Patchbay's `INBOUND_API_KEY`) only to requests to Patchbay. `BusTokenInterceptor` and `BUS_TOKEN` are removed.
- New core module `patchbay/`: the core packages (Mailpit and pdf-renderer contracts, the two integrations and their payload definitions) and the image `cib7-poc-patchbay` built FROM a pinned `krixerx/patchbay` release. Every pack adds a thin layer `docker/patchbay.Dockerfile`, as for the other images.
- Patchbay's configuration (base URLs, secrets, admin login, inbound key, call log) is set in the compose files and `.env`; its `/config` is not persisted, so the packages in git are the only source of integrations.
- The `esb/` module, the `cib7-poc-esb` image, the `esb-conventions` skill and the bus networks are removed.
- The Patchbay console becomes reachable at `https://companylab.ai/patchbay/` behind Patchbay's own admin login (HTTP Basic over TLS), rate limited, with only the console and admin paths routed. The integration API stays unrouted. This needs an upstream Patchbay release with sub-path support, done in the Patchbay repository.
- Patchbay logs to stdout (no GELF), named as an exemption in `docs/logging.md` and `docs/security.md`.

## Capabilities

### New Capabilities

- `outbound-integration`: how the engine reaches anything outside itself: Patchbay integrations by slug with the core's neutral payload definitions, direct backend calls, and which credential is attached to which destination.
- `pack-integration-boundary`: what a service pack may call from its BPMN and what its Patchbay packages may contain, checked by tests tagged `pack`.
- `integration-console`: the Patchbay console and admin API on a public deployment: who can reach which path, and how that is proven at deploy.

### Modified Capabilities

None. `openspec/specs/` is empty; the bus behaviour was never recorded as a spec.

## Impact

- **Engine (`cib7/`):** `BusConfiguration`, `BusTokenInterceptor` and their tests replaced; `ReservedBeansPlugin.RESERVED_NAMES`; `application.yaml`; `PackManifest`; `PackBusTest` replaced by a pack test over BPMN connectors and Patchbay packages; `TemplateExamplesTest` inputs change through the specs.
- **Backend (`backend/`):** `PackManifest` major, descriptor `platform: 3`; `/api/internal/**` callers change from the bus to the engine (no code change in the filter).
- **Pack (`packs/reference/`):** every service-task spec (22 files) with mail, PDF or backend calls, regenerated BPMN and templates through the service-builder, `pack.yaml`, backend descriptors, new `docker/patchbay.Dockerfile`.
- **Patchbay repository** (`krixerx/pathchbay`): sub-path support and release `v0.2.0`.
- **Deployment:** `docker-compose.yml`, `docker-compose.prod.yml`, `deploy/docker-compose.yml`, both `.env.example`, `deploy/deploy.sh` smoke tests, `deploy/traefik/dynamic/routes.yml.example`, `.github/workflows/docker-publish.yml` and `deploy.yml`, `.github/dependabot.yml`, both `graylog/provision-inputs.sh` copies.
- **Docs and agent context:** `CLAUDE.md`, `README.md`, `AGENTS.md`, `docs/platform-api.md`, `architecture.md`, `cib7.md`, `security.md`, `logging.md`, `deployment.md`, `ci-cd.md`, `deploy/README.md`, the service-builder skill and its spec template, `deployment-conventions` (D6), `backend-conventions`, `.claude/rules/esb.md`, `.claude/agents/_stack.md`, `_standards.md`, `_team.md`, `programmer-backend.md`, `.claude/commands/review.md`.
- **Dependencies:** adds `krixerx/patchbay` (pinned); drops Apache Camel JBang and its Trivy ignore file.
