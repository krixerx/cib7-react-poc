# Handoffs

## Regenerate the reference pack for platform API 3.0

- From: architect
- To: process-modeller
- Status: done (5.4, 6.1 and 8.1-8.3 verified 2026-11-26: both Maven gates, pack-check, patchbay-layer-check and the local end-to-end run are green)
- Task: 5.1 to 5.4 in tasks.md

Every service-task spec under `packs/reference/docs/business/services/*/service-tasks/` changes its URL: mail (`send-*.md`, 9 files) to `${patchbayBaseUrl}/api/v1/integrations/mail-send`, PDF (`generate-*-pdf.md`, 4 files) to `${patchbayBaseUrl}/api/v1/integrations/pdf-render`, backend calls (`store-*`, `attach-*`, `look-up-vehicle`, `quote-*-state-fee`) to `${backendBaseUrl}/api/...` with the same paths as today. Mail templates move from Mailpit's schema (`From`, `To`, `Subject`, `Text`, `Attachments` with `Filename`/`ContentType`/`Content`) to the neutral `mail-send` v1 model in design.md D4: `from {email, name}`, `to [{email, name}]`, `subject`, `text`, `attachments [{filename, contentType, base64}]`. Keep `?json_string` on every value. PDF templates and response mappings keep their shape. Update each `## Example` expectation to the new JSON pointers. The service-builder skill and its spec template (5.1) change first, in the same commit as the regenerated output, together with `pack.yaml` `platform: "3.0"`, `platform: 3` in every backend descriptor, and a new `packs/reference/docker/patchbay.Dockerfile` (design D8). Wait for group 2 (the new beans and the bean restriction) so `TemplateExamplesTest` and `scripts/pack-check.sh` can pass. Task 5.4 also moves both `PackManifest` constants to 3.0 with programmer-backend in the same step, so the version equality test is never red. `packs/reference/patchbay/` stays empty (`.gitkeep`); the reference pack has no integration of its own.

Progress (process-modeller, 2026-11-26): 5.1 to 5.3 done in the working tree, uncommitted (skill, spec template, all 22 service-task specs, both READMEs, `data/vehicles.md`). Still open, so this stays open: 5.4. One leftover for 5.4: `.claude/skills/service-builder/SKILL.md` step 10c still says `platform: 2` for the consent descriptor (the edit was refused by the session's permission check; change it to `platform: 3` with 5.4).

## Pack boundary test and negative checks

- From: architect
- To: tester
- Status: done (5.4, 6.1 and 8.1-8.3 verified 2026-11-26: both Maven gates, pack-check, patchbay-layer-check and the local end-to-end run are green)
- Task: 4.1, 4.2, 8.1 to 8.3, 11.1 in tasks.md

`PackIntegrationTest` replaces `PackBusTest` and implements every scenario in `specs/pack-integration-boundary/spec.md`, each rule with a negative fixture. The interceptor's negative cases are listed in task 2.2 (programmer-backend writes them; review that they cover a host-suffix trick, userinfo, dot segments and a BPMN-set header). On the running stack, prove the scenarios of `specs/outbound-integration/spec.md` (401 without `X-API-Key`, mail with attachment delivered, no token outside `/api/internal/`) and, after phase 3, of `specs/integration-console/spec.md`.

## Sub-path support in Patchbay

- From: architect
- To: programmer-backend (in the Patchbay repository, krixerx/pathchbay)
- Status: open
- Task: 9.1 to 9.3 in tasks.md

The console must work under `https://companylab.ai/patchbay/` behind Traefik with StripPrefix. Root assumptions found: `engine/console/vite.config.ts` (default base `/`), `engine/console/src/api.ts:37` (`fetch('/admin/api' + path)`), `engine/console/src/pages/IntegrationsPage.tsx:129` (`href="/api/openapi.json"`), `engine/console/src/pages/board/PublishPanel.tsx:149-161` (`window.location.origin`). The server matches root paths (`RequestPaths`, `AdminRequestFilter.CONSOLE`) and can keep doing so, because the proxy strips the prefix; `sameOrigin` compares `Origin` with `Host`, which Traefik keeps. Do not use a servlet context path (design D12). Release as `v0.2.0` and tell the architect the tag, so task 10.1 can pin it.

## The engine's secret guard needs APP_REQUIRE_REAL_SECRETS on cib7

- From: programmer-backend
- To: programmer
- Status: done
- Task: 6.1, 6.2 in tasks.md

Task 2.3 added `cib7/src/main/java/com/poc/cib7/secrets/DefaultSecretsGuard.java`: the engine refuses to start while `PATCHBAY_API_KEY` or `INTERNAL_TASK_TOKEN` is its `-change-me` default (`patchbay-api-key-change-me`, `internal-task-token-change-me`), but only when the `prod` profile is active or `APP_REQUIRE_REAL_SECRETS=true`. Today only the backend service sets `APP_REQUIRE_REAL_SECRETS: "true"` (`docker-compose.prod.yml`). Set it on the `cib7` service in `docker-compose.prod.yml` and `deploy/docker-compose.yml` too, or the guard only warns. The engine now reads `PATCHBAY_URL`, `PATCHBAY_API_KEY`, `BACKEND_URL` and `INTERNAL_TASK_TOKEN`; `BUS_URL` and `BUS_TOKEN` are no longer read.

Done (group 6): `APP_REQUIRE_REAL_SECRETS: "true"` on `cib7` in `docker-compose.prod.yml`; in `deploy/docker-compose.yml` it is `${APP_REQUIRE_REAL_SECRETS:-true}`, because the kit promises a single-machine start without real secrets, which a hard `true` would break. The kit's quick start now writes `APP_REQUIRE_REAL_SECRETS=false` into `.env`, and `deploy.sh` refuses that value on a public host. The engine gets `PATCHBAY_URL`, `PATCHBAY_API_KEY`, `BACKEND_URL` and `INTERNAL_TASK_TOKEN` in all three compose files; `BUS_URL`/`BUS_TOKEN` are gone.

## Restricting beans does not stop a pack BPMN from reaching secrets

- From: programmer-backend
- To: architect
- Status: done
- Task: 2.4 in tasks.md (design D2, D14)

Task 2.4 is done: only `ReservedBeansPlugin.RESERVED_NAMES` resolve by name in JUEL and FreeMarker (`BeanExposureTest`). Before the fix, on CIB seven 2.2, `${environment.getProperty('INTERNAL_TASK_TOKEN')}` in a manual task's output parameter returned the token, and `${outboundCredentialsInterceptor}` resolved in JUEL and in FreeMarker; `environment` did not resolve in FreeMarker (the Spring script resolver lists bean definitions only).

After the fix I probed paths that name no bean (scratch test, not committed). All of these still work in a deployed BPMN:

- JUEL `${execution.processEngine.processEngineConfiguration}` reaches `SpringProcessEngineConfiguration`, and from there `.dataSource` (a `TransactionAwareDataSourceProxy`).
- JUEL `${''.getClass().forName('java.lang.System').getMethod('getenv', ''.getClass()).invoke(null, 'PATH')}` returned the environment variable, so the same reads `INTERNAL_TASK_TOKEN` or `PATCHBAY_API_KEY` in the container.
- FreeMarker `"freemarker.template.utility.ObjectConstructor"?new()` constructs arbitrary classes; FreeMarker's `?new` default resolver also allows `Execute` (not tried).
- FreeMarker `${execution.processEngine.processEngineConfiguration...}` works like JUEL.

So "no pack can read an engine secret through an expression" (proposal) does not hold yet: a pack's BPMN and templates are effectively trusted code. Options to decide: (a) the pack check (`PackIntegrationTest`/`PackConformanceTest`, task 4.1) refuses method calls and `execution.processEngine`/`getClass` in connector inputs and outputs and `?new`/`?api` in templates, (b) the engine sets FreeMarker's `new_builtin_class_resolver` to `ALLOWS_NOTHING_RESOLVER` and `api_builtin_enabled=false`, and a JUEL resolver refuses `getClass`, `forName` and `processEngine`, (c) accept and record that a pack is reviewed code (docs/security.md). (a) plus (b) together is defence in depth; neither is in group 2.

Architect, 2026-11-26: still open, waiting for the user's decision on the trust model; the architect does not decide it alone. Options and the recommendation are in design.md, Open Questions ("The trust model for pack BPMN and templates"). In short: (a) an allowlist of the expression forms the service-builder writes, plus refusing `?new`, `?api`, `execution.processEngine…` and `getClass` in templates and documents, in the pack tests; a real gate for the reference pack, the only pack today, because `docker-publish.yml` builds its layers only after the `quality` job. (b) runtime guards: the payload templates' FreeMarker gets the settings `DocumentRenderer` already has (how the script engine exposes its `Configuration` is not verified), and a JUEL resolver refuses reflection types and `processEngine…` navigation (a blocklist on CIB seven internals, unproven). (c) accept and record that a pack is reviewed code. **Recommendation: (c) now, plus (a) and the FreeMarker half of (b) in this change, the JUEL guard before a second pack or one built outside this repository.** Meanwhile docs/platform-api.md says only what `BeanExposureTest` proves ("no other Spring bean resolves by name") and that this is not a sandbox yet; D2 records that the gap is narrowed, not closed. Depends on the decision: the proposal bullet "no pack can read an engine secret through an expression" and the `pack-integration-boundary` requirement "A pack's expressions cannot read engine configuration", both left unchanged until then; and the scope of tasks 4.1 (tester) and 7.3 (docs/security.md).

Architect, 2026-11-26, decision (the user chose the recommendation; design.md D17): a pack is reviewed code with the engine's power. In this change: the static check in `PackIntegrationTest` (task 4.1, the tester's reflection check), FreeMarker `?new`/`?api` refused in payload templates (new task 2.6, programmer-backend, with a negative test), and `docs/security.md` records the model (task 7.3). The JUEL runtime guard is a recorded follow-up before a second pack or one built outside this repository's CI (tasks.md, Workflow follow-up). The proposal bullet and the `pack-integration-boundary` spec now say what is guaranteed: no other bean resolves, payload templates refuse `?new`/`?api`, and the pack check refuses reflection-like expressions.

## mail-send v1 as built: optional names, empty `to`

- From: programmer-backend
- To: architect
- Status: done
- Task: 4.3 in tasks.md (and 8.2 for the tester)

The core package `patchbay/packages/10-core-mail/` (task 1.2) defines `mail-send` v1 as: `from` (object, required) with `email` required and `name` optional; `to` (list, required) of objects with `email` required and `name` optional; `subject` and `text` required; `attachments` optional, each item with `filename`, `contentType` and `base64` all required. Output `mail-send-result`: `messageId` (optional, from Mailpit's `ID`). `name` is optional because Mailpit's `Name` is; today's templates always send one. `pdf-render` v1: input `html` and `filename`, both required (the renderer and `pdf-renderer/openapi.json` treat `filename` as optional and default it to `document.pdf`; the model requires it because every template sends one); output `filename` and `base64`. Non-ASCII (em dash, Estonian, Arabic) passes through `mail-send` unchanged, with and without `charset=utf-8` on the request. Document these in "Integrations offered to a pack".

One gap against `specs/outbound-integration/spec.md` ("`to` (a non-empty list ...)"): Patchbay 0.1.0's `required` checks only that a field is present, so `"to": []` passes Patchbay's validation. The call then reaches Mailpit, which answers 400 `{"Error":"error building message: no recipients (to, cc, bcc) set"}`, passed through to the engine; no mail is sent. Missing `to` is refused by Patchbay itself with 400 `{"error":"bad-request",...,"problems":["to: required"]}` and Mailpit is not called (verified). Either word the spec and the platform API doc as "the mail system refuses an empty `to`", or open an upstream Patchbay task for a minimum item count.

Architect, 2026-11-26, decision (design.md D4, "As built"): the guarantee is stated where it holds. Patchbay refuses a missing required field with 400 before any call; an empty `to` passes Patchbay, is refused by the mail system with 400, and sends no mail. `docs/platform-api.md` ("Integrations offered to a pack", `mail-send` v1) documents the models as built and says this; `specs/outbound-integration/spec.md` drops "non-empty" from the model sentence, narrows the 400 rule to a missing required field and adds the scenario "Empty recipient list". BPMN keeps guarding a possibly missing recipient before the call. A minimum item count in Patchbay is an upstream follow-up (design.md Open Questions), not a phase 1 blocker. Not claimed anywhere: that Patchbay checks required fields inside list items (not tested).

## Zip stage for the pack's patchbay.Dockerfile

- From: programmer-backend
- To: process-modeller
- Status: done (5.4, 6.1 and 8.1-8.3 verified 2026-11-26: both Maven gates, pack-check, patchbay-layer-check and the local end-to-end run are green)
- Task: 5.4 in tasks.md

`patchbay/Dockerfile` (task 1.5) zips with `for d in */; do (cd "$d" && zip -qrXD "/import/${d%/}.zip" .); done` on `alpine:3.24.4` with `zip`, then `ls /import/*.zip`. Do not copy it unchanged into `packs/reference/docker/patchbay.Dockerfile`: the reference pack's `patchbay/` holds only `.gitkeep`, so `*/` stays a literal and the loop and the `ls` fail. Guard the loop (`[ -d "$d" ] || continue`), drop the `ls`, name each ZIP `50-<folder>.zip` (design D8), and `COPY --from` a folder that may be empty (`RUN mkdir -p /import` in the stage). Build the layer once with an empty `patchbay/` to prove it.

## Prove the two mail-send refusals on the running stack

- From: architect
- To: tester
- Status: done
- Task: 8.2 in tasks.md

`specs/outbound-integration/spec.md` now has two refusal scenarios for `mail-send`: "Missing recipient" (no `to`: Patchbay answers 400 with `"problems": ["to: required"]` and Mailpit is not called) and "Empty recipient list" (`"to": []`: Patchbay passes it, Mailpit answers 400 `no recipients`, no mail is sent). Prove both on the running stack with `X-API-Key` set, and check the Mailpit inbox count does not change. Record the commands here.

## Pack tests on packs/reference are red between 5.3 and 5.4, by design

- From: process-modeller
- To: tester
- Status: done (5.4, 6.1 and 8.1-8.3 verified 2026-11-26: both Maven gates, pack-check, patchbay-layer-check and the local end-to-end run are green)
- Task: 4.1, 4.2 (context); 5.2, 5.4 in tasks.md

The service-task specs under `packs/reference/docs/business/services/*/service-tasks/` now carry the `mail-send`/`pdf-render` templates and the `${patchbayBaseUrl}`/`${backendBaseUrl}` URLs, but the generated output (`packs/reference/engine/templates/*.json.ftl`, the BPMN) is regenerated only in task 5.4. Until then, on `packs/reference`, expect `ServiceSpecsTest` to fail (each spec's ftl block differs from its template file) and `TemplateExamplesTest` to fail (it takes variables and expectations from the spec but loads the template from the generated `templates/` on the classpath, so the new pointers `/text`, `/to/0/email`, `/from/email`, `/attachments/0/base64` miss). Do not chase these; they turn green with 5.4. A failure in any other test is real. Close this entry when 5.4 lands.

## FreeMarker hardening of payload templates

- From: architect
- To: programmer-backend
- Status: done
- Task: 2.6 in tasks.md (design D17)

Payload templates (`engine/templates/*.json.ftl`, rendered by the engine's FreeMarker script engine) must refuse `?new` and `?api`, with the settings `DocumentRenderer` already uses (`TemplateClassResolver.ALLOWS_NOTHING_RESOLVER`, `setAPIBuiltinEnabled(false)`). Nobody has checked how the script engine exposes its `Configuration`; find that first. Negative test: a deployed BPMN whose payload template does `"freemarker.template.utility.ObjectConstructor"?new()` fails to render and sends no request. The tester is editing `cib7/src/test` for 4.1 and 4.2 right now, so put the new test in its own file. Spec: `specs/pack-integration-boundary/spec.md`, scenario "A payload template constructs a class".

Done by programmer-backend: `cib7/src/main/java/com/poc/cib7/PayloadTemplatesPlugin.java` registers a restricted FreeMarker script engine factory; the mechanism is recorded in `docs/cib7.md` (`PayloadTemplatesPlugin.java`). Negative test: `PayloadTemplateHardeningTest` (new file). `?api` was already off by FreeMarker default; the test pins it.

## The reflection scan in PackIntegrationTest is bypassable and is not the fix

- From: tester
- To: architect
- Status: open
- Task: 4.1 in tasks.md (answers option (a) of "Restricting beans does not stop a pack BPMN from reaching secrets")

`PackIntegrationTest.packExpressionsAndTemplatesUseNoReflection` scans every `.bpmn`, `.dmn`, `.ftl` and `.ftlh` under a pack's `engine/processes`, `templates` and `documents` for the plain spellings `getClass`, `forName`, `processEngine` (covers `processEngineConfiguration`), `?new`, `?api`, `Execute` and `ObjectConstructor`. It passes on `packs/reference` today (no false positive). It is a text scan: string concatenation (`'get'.concat('Class')`), `?interpret`, reflection through a method returning a Class, unicode escapes and any other path to the same objects get past it. It catches the honest mistake and the lazy attack only. The decision in that entry (engine-side FreeMarker `new_builtin_class_resolver`/`api_builtin_enabled`, a JUEL resolver, or accepting that a pack is reviewed code in docs/security.md) is still open and still needed. If the engine guard lands, add a deployed-BPMN negative test per path (`BeanExposureTest` style) and keep or drop the scan as a cheap early signal.

## Pack layer check: what 5.4 must provide

- From: tester
- To: process-modeller
- Status: done (5.4, 6.1 and 8.1-8.3 verified 2026-11-26: both Maven gates, pack-check, patchbay-layer-check and the local end-to-end run are green)
- Task: 5.4 in tasks.md

`PackIntegrationTest.aPacksPatchbayLayerDockerfileZipsOnlyItsPackages` and `scripts/patchbay-layer-check.sh packs/reference` (CI job `patchbay-pack-layer` in `quality.yml`) are red until `packs/reference/docker/patchbay.Dockerfile` exists. The Dockerfile must: declare `ARG PATCHBAY_CORE` before the first `FROM`; have an earlier stage (`FROM ... AS <name>`) with a `RUN` that calls `zip -...` and writes `/import/50-<name>.zip`; end with `FROM ${PATCHBAY_CORE}` and `COPY --from=<that stage> /import/ /import/`; `COPY` from the build context only `patchbay/...`; no `ADD`, no `RUN --mount`. The version proven in a dry run (empty `patchbay/` plus a fixture package, container showed all three integrations active) is: guard the loop with `[ -d "$d" ] || continue`, `RUN mkdir -p /import` first, no `ls`. Add `docker/patchbay.Dockerfile.dockerignore` (`*` then `!patchbay`) like the other layers (not checked). The build context is the pack directory (`docker build -f docker/patchbay.Dockerfile --build-arg PATCHBAY_CORE=... <pack>`). `PackIntegrationTest` also fails a pack that still has `esb/` or `docker/esb.Dockerfile`; the reference pack has neither.

## Stale PackBusTest mentions

- From: tester
- To: architect
- Status: done
- Task: 4.3 in tasks.md

`PackBusTest` is gone. `.claude/skills/esb-conventions/SKILL.md` still names it, and so may `docs/` outside platform-api.md (grep `PackBusTest`). The esb skill belongs to the esb removal task, not 4.x.

Done by programmer, 2026-11-26 (tasks 7.2, 7.3): the esb skill is replaced by `patchbay-conventions`, and `docs/security.md` rule 9 names `PackIntegrationTest`. `grep -rn PackBusTest` outside this change folder finds nothing.

## Keep the deploy copy of the Graylog init script in sync

- From: programmer (group 7)
- To: programmer (group 6)
- Status: done
- Task: 7.1 in tasks.md (file in group 6's `deploy/**`)

Task 7.1 removed the GELF TCP input from `graylog/provision-inputs.sh`; only the esb shipped over TCP (cib7 and backend use `GelfUdpAppender`, mcp and pdf-renderer UDP sockets). `deploy/graylog/provision-inputs.sh` must stay a copy, so make the same edit there: in the header, line 2 "Creates the two GELF inputs" becomes "Creates the GELF input", line 6 "POSTs the inputs" becomes "POSTs the input", and lines 10 to 12 (the "Two inputs on the same port number" block) become the four lines now at 10 to 13 of `graylog/provision-inputs.sh`; keep the "Copy of ../../graylog/provision-inputs.sh" paragraph. At the end, delete the `use_null_delimiter` comment and the whole `create_input 'GELF TCP' ...` call, and change the last line to `log "done — input provisioned."`. Afterwards `diff graylog/provision-inputs.sh deploy/graylog/provision-inputs.sh` must show only the "Copy of" paragraph. Three compose comments still describe the TCP input (checked 2026-11-26): `docker-compose.yml:882` ("the GELF UDP/TCP input"), `docker-compose.yml:925` ("the two GELF inputs") and `deploy/docker-compose.yml:735` ("the GELF UDP + TCP inputs").

Done (group 6): `deploy/graylog/provision-inputs.sh` rebuilt from `graylog/provision-inputs.sh` plus the "Copy of" paragraph; `diff` shows only that paragraph. The three compose comments and the matching lines in `docs/architecture.md` and `deploy/README.md` now name the one UDP input.

## Group 6: what is left to prove on a running stack

- From: programmer
- To: tester
- Status: done (5.4, 6.1 and 8.1-8.3 verified 2026-11-26: both Maven gates, pack-check, patchbay-layer-check and the local end-to-end run are green)
- Task: 6.1, 6.2, 6.3 in tasks.md; 8.x

Not run in group 6, because another local project holds ports 3000, 8180, 8085, 8090 and 8080: `docker compose up --build` with every service healthy (6.1). Checked instead: `docker compose config` for the dev file, the prod overlay and the kit (with and without the `tls` and `mail` profiles); the rendered output has `patchbay` on `integration`, `mail`, `pdf` only, Graylog on no Patchbay network, one loopback port for Patchbay in every file, and the engine with the four new variables and no `BUS_*`. `patchbay-core` and `patchbay` build; the built layer, run alone with `--tmpfs /config:uid=10001`, answers `GET /admin/integrations` with `{"broken":{},"active":{"mail-send":"1.0.0","pdf-render":"1.0.0"}}`. On the running stack, prove: the engine starts after Patchbay is healthy; one vehicle case sends mail and stores both PDFs; the engine with `APP_REQUIRE_REAL_SECRETS=true` and a dev-default `PATCHBAY_API_KEY` refuses to start (prod overlay); `deploy.sh`'s new post-deploy steps (Patchbay readiness, the `/admin/integrations` check) pass on a real kit run. The new host checks of `deploy.sh` were tested in isolation only (26 cases: fake `.env` and `patchbay-pack.env` in a temp dir, plus the integrations parser on fixtures).

## Group 6: VM Docker Compose version for the optional env_file

- From: programmer
- To: architect
- Status: open
- Task: 6.2 in tasks.md

`deploy/docker-compose.yml` now reads `patchbay-pack.env` through `env_file` with `required: false`, which needs Docker Compose 2.24.0 or later (Docker docs, "Set environment variables"). Verified locally on Compose v5.5.0; the VM's version was not checked, because group 6 must not touch the VM. Run `docker compose version` on the VM before the first deploy of this kit; `deploy/README.md` (Prerequisites) says so. Two smaller points for review: the prod overlay keeps `MAPPER_URL_MAILPIT=http://mailpit:8025` (no `/mailpit`), because only the kit sets `MP_WEBROOT`; and `CLAUDE.md` still says "platform API `2.0`" in "The platform API" paragraph, outside the parts group 6 owns.

Decision for review: in the deploy kit `APP_REQUIRE_REAL_SECRETS` is `${APP_REQUIRE_REAL_SECRETS:-true}`, not the literal `"true"` the handoff asked for, so the kit's single-machine start keeps working with one `.env` line (`APP_REQUIRE_REAL_SECRETS=false`). `deploy.sh` refuses `false` on a public host and, on a local run, fails with a hint when the engine would refuse its defaults. The kit's backend does not set the variable, so there only the engine refuses. The alternative is the literal `"true"`, with the quick start requiring two real secrets.

## Patchbay 0.1.0 accepts integration calls when INBOUND_API_KEY is empty

- From: programmer
- To: architect
- Status: open (engine side done: DefaultSecretsGuard now refuses a blank or missing key, DefaultSecretsGuardTest.blankSecretIsRefusedWhenRealSecretsAreRequired; the upstream Patchbay fix remains)
- Task: 6.2 in tasks.md; upstream (Patchbay repository)

Measured on the built `cib7-poc-patchbay` (0.1.0): with `INBOUND_API_KEY` set to an empty string, `POST /api/v1/integrations/pdf-render` with no `X-API-Key` header, an empty one or a wrong one is passed on to the external system (502 from the unreachable pdf-renderer, where a set key gives 401). An empty `MAPPER_ADMIN_PASSWORD` makes Patchbay refuse to start, so the console fails closed. The engine's `DefaultSecretsGuard` compares with the `-change-me` default only and lets an empty key through. Group 6 closed the gap in compose: the prod overlay now uses `${PATCHBAY_API_KEY:?...}` and `${PATCHBAY_ADMIN_PASSWORD:?...}`, and the dev file and kit use `:-` defaults, which also replace an empty value, with `deploy.sh` refusing an unset one on a public host. Upstream, Patchbay should refuse to start on an empty `INBOUND_API_KEY` the way it does for the admin password; and the engine guard could treat blank like the default. Not fixed here.

## Phase 1 proven on a local stack (8.1 to 8.3), commands for 8.2

- From: tester
- To: architect
- Status: open
- Task: 6.1, 8.1, 8.2, 8.3 in tasks.md

Run 2026-11-26 on `docker compose up --build` (branch feat/replace-esb-with-patchbay, working tree), Mailpit UI via `--profile dev`, dev-default secrets. Open for the architect only because of the items under "Findings" and "Not proven".

**Setup note.** The persisted `postgres-data` volume of the earlier run held process instances of the old BPMN; their jobs failed with `Cannot resolve identifier 'busBaseUrl'`. I removed that one volume (`docker volume rm cib7-react-poc_postgres-data`, not `down -v`; the Graylog volumes were kept) and started again. This is the "migrate or wipe running process instances" risk of task 8.4: the VM's running instances need the same wipe or they incident on `busBaseUrl`.

**8.1.** Cases driven over `/engine-rest` with the Bearer token of a real Keycloak login (Playwright login, token read from the SPA's request; the realm has no direct-grant client), co-owner and payment steps through the public APIs with the links from the mails.
- Vehicle A (sole owner, VIN WP0AB2A91KS123456, value 88000, so DMN `review`): bart submits, homer waits 150 s then approves. Mails in Mailpit: the reviewer reminder (2 min timer, no attachment) and "Your vehicle registration is approved" with `state-fee-invoice-WP0AB2A91KS123456.pdf`. Pay link from the mail, `POST /api/public/payments/<token>/checkout`, `POST /api/public/mock-provider/sessions/<id>/pay`, case ends COMPLETED. Case documents (`GET /api/documents/<pi>` as bart): `generated-approval-pdf` and `generated-certificate`.
- Business B (Acme OÜ, sole founder): review approve, mail "Estonian OÜ registered: Acme OÜ" with a PDF, same payment steps, COMPLETED, documents `generated-business-fee-invoice` and `generated-certificate`.
- Vehicle C (one co-owner, lisa@example.com): tracking mail to bart, signing mail to lisa, lisa approves and sends through `/api/public/consent/owner/<token>` and `/send`, homer sends back, "needs corrections" mail to bart. Instance deleted afterwards.
- Every mail attachment I fetched (`/api/v1/message/<id>/part/<n>`) starts with `%PDF-`. Patchbay Calls (`GET /admin/api/integrations/<slug>/calls`, HTTP Basic admin): `mail-send` 9 calls (7 x 200 from the engine and my control, 2 x 400 = the refusals below), `pdf-render` 4 x 200 (114 to 444 ms).
- Cockpit: `GET /engine-rest/incident` as admin empty and `job?noRetriesLeft=true` empty at the end. One earlier instance of mine made an incident at `Task_AttachIdDocument` because my driver sent a Json variable holding the text `null`; the SPA sends a real null (`values.test.ts`), so that was the driver, not a defect, and the instance was deleted before the final check. I did not open the Cockpit UI.

**8.2 commands** (from the repo root; key and token are the compose dev defaults):

```
# no key and wrong key, from cib7 (shares the integration network): 401
docker compose exec -T cib7 curl -s -w ' HTTP %{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' http://patchbay:8080/api/v1/integrations/mail-send
  -> {"error":"unauthorized",...,"detail":"Send the X-API-Key header"} HTTP 401
same with -H 'X-API-Key: wrong' -> "The X-API-Key header does not match" HTTP 401 (also pdf-render without a key)

# backend internal, from the frontend container (network app): 401 without and with a wrong token
docker compose exec -T frontend curl -s -w ' HTTP %{http_code}\n' http://backend:8085/api/internal/registry/vehicles
  -> {"code":"invalid_internal_token","message":"X-Internal-Token missing or invalid."} HTTP 401
same with -H 'X-Internal-Token: wrong' -> 401
same with -H 'X-Internal-Token: internal-task-token-change-me' -> 404 (past the filter; the path I guessed has no handler)

# Patchbay egress: none resolves
docker compose exec -T patchbay curl -s -m 5 -o /dev/null -w '%{http_code}' <url>
  http://backend:8085/actuator/health, http://keycloak:8080/, http://graylog:9000/, http://postgres:5432/
  -> curl exit 6 (could not resolve host) for all four
controls: from patchbay, http://mailpit:8025/api/v1/info 200 and http://pdf-renderer:8088/health 200

# mail-send refusals, X-API-Key set, from cib7 (Mailpit total 0 before; counted by unique subject)
{"from":{"email":"a@example.com"},"subject":"REFUSE82missing","text":"x"}
  -> 400 {"error":"bad-request","integration":"mail-send",...,"problems":["to: required"]}; Mailpit subject count 0
{"from":{"email":"a@example.com"},"to":[],"subject":"REFUSE82empty","text":"x"}
  -> 400 {"Error":"error building message: no recipients (to, cc, bcc) set"} (Mailpit's own); count 0
control with one recipient and an attachment -> 200 {"messageId":"..."}; count 1; inbox total 0 -> 1 (only the control)
```

In the Patchbay Calls view the `to: []` call took 72 ms (it reached Mailpit), the missing-`to` call 3 ms (refused by Patchbay).

**8.3.** `E2E_BASE_URL=http://localhost:3000 npm test` in `e2e/`: 2 passed (login, navigation). Port 3000 was this stack's frontend (`docker ps` checked first). The suite has no journey that starts a case; the cases above were driven by scratch scripts that I did not add to `e2e/`.

**6.1 and group 6 checks.** `docker compose up --build`: all services up and healthy; the engine started after Patchbay was healthy; `GET /admin/integrations` is `{"broken":{},"active":{"mail-send":"1.0.0","pdf-render":"1.0.0"}}`. Engine guard, on a scratch database (`guardtest`, created and dropped; second engine via `docker compose run --rm --no-deps -e ENGINE_DB_URL=...`): `APP_REQUIRE_REAL_SECRETS=true` with the dev-default key and token refuses to start (`Refusing to start: dev-default secrets in effect for [app.backend.internal-token, ...]`).

**Findings.**
1. DefaultSecretsGuard and a blank key: the same run with `APP_REQUIRE_REAL_SECRETS=true`, `PATCHBAY_API_KEY=` (empty) and a real `INTERNAL_TASK_TOKEN` starts normally (`Started Cib7PocApplication`). The guard compares with `equals` against the default only (`DefaultSecretsGuard.java`, `DEV_DEFAULTS`), so a blank value passes and no WARN is logged. With Patchbay 0.1.0 accepting any call when `INBOUND_API_KEY` is empty (entry above), a blank `PATCHBAY_API_KEY` that reaches the engine is neither refused nor warned. Compose `:-` defaults and the prod overlay's `:?` keep it out of the shipped files, so this bites only a hand-made env. For programmer-backend: treat blank like the default, with a case in `DefaultSecretsGuardTest`.
2. Patchbay can reach `cib7:8080` (HTTP 302, the engine's login redirect), because both sit on the `integration` network by design D11. Not a failure of the three targets of 8.2; the architect may want it named in the pack trust model of `docs/security.md`.

**Not proven.** `deploy.sh`'s post-deploy steps on a real kit run (needs a host; belongs with 8.4); the prod overlay starting; the Cockpit UI itself; 8.4 and everything after group 8.
