# Design

## Context

Today the engine knows one outbound address, `${busBaseUrl}` (`BusConfiguration`, `http://esb:8080`). `BusTokenInterceptor` stamps `X-Bus-Token` on every connector call to it, and the Camel routes in `esb/routes/` check that token (`bus-auth.yaml`), forward mail verbatim to Mailpit (`notification-route.yaml`), PDF requests verbatim to the pdf-renderer (`pdf-route.yaml`), and backend calls path-preserving to `backend:8085`, injecting `X-Internal-Token` on `/api/internal/**` (`backend-route.yaml`). Because the bus forwards verbatim, the 22 service-task specs of `packs/reference` write Mailpit's and the renderer's own schemas. `PackBusTest` (tagged `pack`) holds a pack's BPMN to the offered bus paths and its `esb/routes/pack-*.yaml` to safe Camel steps.

Patchbay (`krixerx/patchbay`, only release `v0.1.0`, which is the repository's HEAD on 2026-11-26) serves each published mapping at `/api/v1/integrations/<slug>`, validates the caller's input against a payload definition, maps it to the external request, and maps the answer back. Its facts that shape this design, read in its `README.md`, `engine/README.md` and source:

- Configuration is JSON in `/config`; packages are ZIPs of that layout with a `manifest.json` (`format`, `formatVersion: 1`, `Packages.java`). `MAPPER_IMPORT_DIR` imports every `*.zip` at start in file-name order, replacing definitions, contracts and drafts with the same id, but **keeps an existing `activeVersion`**, and refuses a version number that already exists with other content.
- Base URLs (`MAPPER_URL_<ID>`), connection headers and secrets come from the environment; packages never carry connections or secrets, but older version files may carry a literal `target.baseUrl`.
- `INBOUND_API_KEY` requires `X-API-Key` on integration calls, compared with `MessageDigest.isEqual` (`InboundKeyFilter`). `MAPPER_ADMIN_USER`/`MAPPER_ADMIN_PASSWORD` set the HTTP Basic login of the console and `/admin`; there is no lockout. Caller headers never reach the external system.
- The console is a hash-routed SPA that fetches `/admin/api...` (`console/src/api.ts:37`), loads `/assets/...` (Vite default base) and links `/api/openapi.json`. The server filters decide on the path within the application (`RequestPaths`, `AdminRequestFilter.CONSOLE`), and the same-origin check compares `Origin` with the `Host` header.
- The image runs as uid 10001, declares `VOLUME /config`, has a `curl` health check on `/actuator/health/readiness`, logs to stdout only.

## Goals / Non-Goals

**Goals:**

- Mail and PDF calls leave the engine in the platform's own neutral models; the mapping, address and credentials of Mailpit and the renderer live in Patchbay, set at compose level.
- A pack adds an external integration as data (a Patchbay package), checked by a `pack` test, without any way to reach core secrets or hosts.
- Remove `esb/` and everything that only existed for it.
- The Patchbay console is usable at `https://companylab.ai/patchbay/` and nothing else of Patchbay is public.

**Non-Goals:**

- Replacing Mailpit with a real SMTP relay, or the pdf-renderer with a direct Gotenberg call (see D5).
- GELF logging from Patchbay (D13).
- A Keycloak login in front of the console (D12).
- Inbound integrations (an external system calling us). Patchbay could serve them; nothing needs one yet.

## Decisions

### D1. Patchbay replaces the bus for external systems; the core rule changes

The module rule "the bus is the only outbound address" in `CLAUDE.md` and `docs/architecture.md` is replaced by: **the engine reaches external systems only through Patchbay integrations, and the backend only at its own address; it calls nothing else.** "External" means anything that is not one of our own core services; Mailpit and the pdf-renderer count as external, because they are the stand-ins for systems a real deployment swaps out.

*Rejected: keep `esb/` beside Patchbay* for the backend calls. Two integration products for one stack, the bus kept only to hold a token the engine can hold as safely (D2), and Camel JBang kept in the supply chain with its own Trivy history.

### D2. The engine calls the backend directly and holds `INTERNAL_TASK_TOKEN`

New bean `backendBaseUrl` (`BACKEND_URL`, `http://backend:8085`). The engine sends `X-Internal-Token` itself (D3).

Why that is acceptable under `docs/security.md`: the bus's token check (rule 9) protected the bus's power to *add* the token, not the backend. The backend's `/api/internal/**` was already reachable on the networks it shares (`edge`, `app`) by any container there, and the token, checked in constant time by `InternalTokenAuthenticationFilter`, is what closes it; that does not change. The engine already holds secrets of the same weight (`LINK_SIGNING_SECRET`, which mints capability links the backend honours, and `KEYCLOAK_BACKEND_CLIENT_SECRET`, both set on the `cib7` service in `docker-compose.yml`). The engine and the backend already share the `app` network, so no new network path is opened. What must still hold is that a **pack** cannot get the token: the BPMN never contains it, the interceptor adds it only for the backend's internal prefix and strips it everywhere else, and the pack test refuses a connector that sets it (D3, D14).

One gap found while checking this: `PackConformanceTest` forbids `camunda:expression` and delegates, but not expressions in connector inputs, and no engine code restricts which Spring beans JUEL resolves (no `setBeans` in `cib7/src/main/java`). With the Spring engine's default of exposing every bean, a pack's connector input could read `${environment.getProperty('INTERNAL_TASK_TOKEN')}` and post it to its own integration. The same gap exists today for `LINK_SIGNING_SECRET` and `BUS_TOKEN`. This change closes it: the engine configuration exposes only `ReservedBeansPlugin.RESERVED_NAMES` to expressions and templates (`ProcessEngineConfiguration.setBeans`), with a negative test that `${environment}` and the interceptor's bean name do not resolve in a deployed BPMN. Not verified on CIB seven 2.2 yet; the test is the proof.

As built (task 2.4, `BeanExposureTest`), this narrows the gap and does not close it. Paths that name no bean still work in a deployed BPMN on CIB seven 2.2 (programmer-backend's probe, handoff "Restricting beans does not stop a pack BPMN from reaching secrets"): JUEL `${execution.processEngine.processEngineConfiguration}` and from there its `dataSource`; JUEL reflection `''.getClass().forName('java.lang.System').getMethod('getenv', …).invoke(…)`, which reads any environment variable, `INTERNAL_TASK_TOKEN` and `PATCHBAY_API_KEY` included; and in payload templates the same `execution` navigation and `?new` on arbitrary classes. So a pack's BPMN and templates are still code with the engine's power; D17 records what is guaranteed and how the rest is closed.

*Rejected: Patchbay proxies the backend.* Patchbay has no path-preserving proxy: every backend endpoint (document upload, move-pending, payments quote, each registry) would need its own contract, definitions and mapping, and Patchbay would hold the internal token, which makes the console (public, D12) a route to `/api/internal/**` through its Test button.

### D3. One interceptor attaches credentials by destination

`BusTokenInterceptor` becomes `OutboundCredentialsInterceptor` (same plugin mechanism). For each http-connector request it normalizes the URI (scheme, host, port, path with dot segments removed) and then:

- target origin equals `patchbayBaseUrl`'s origin and path starts with `/api/v1/integrations/`: set `X-API-Key` to `app.patchbay.api-key` (`PATCHBAY_API_KEY`);
- target origin equals `backendBaseUrl`'s origin and path starts with `/api/internal/`: set `X-Internal-Token` to `app.backend.internal-token` (`INTERNAL_TASK_TOKEN`);
- otherwise remove both headers.

Origins are compared as parsed values, never by string prefix, so `http://backend:8085.evil` or `http://backend:8085@evil` cannot match. Negative tests: token absent on `/api/public/**`, on Patchbay, on a foreign host, on a userinfo/host-suffix trick, and a BPMN-set header replaced or removed. `RESERVED_NAMES` becomes `patchbayBaseUrl`, `backendBaseUrl`, `frontendBaseUrl`, `pdf`, `links`, `documents`.

*Rejected: a header in the BPMN from a config bean* (`${internalToken}`). The secret would be readable by any pack template.

### D4. Neutral payloads: `mail-send` and `pdf-render`, version 1

Defined as Patchbay payload definitions in the core package and documented in a new section of `docs/platform-api.md`, "Integrations offered to a pack", which replaces "Bus paths a pack may call".

`mail-send` input:

```json
{
  "from": { "email": "process@cib7-poc.local", "name": "Transpordiamet POC" },
  "to": [ { "email": "ants@example.com", "name": "Ants Avaldaja" } ],
  "subject": "…",
  "text": "…",
  "attachments": [ { "filename": "invoice.pdf", "contentType": "application/pdf", "base64": "…" } ]
}
```

`from`, `to` (one or more), `subject`, `text` required; `attachments` optional. Output `{ "messageId": "…" }` from Mailpit's `ID`. `to` is a list because Patchbay cannot link a single value into a list and has no transforms (`engine/README.md`, rule 6 of Lists, and "Not built yet"), and Mailpit's `To` is a list. Attachments carry base64 as text, because Patchbay's `blob` type is top-level only. `from` is in the model, not a constant in the mapping, because the sender name is the pack's brand (today "Transpordiamet POC") and the core mapping must not know a pack. `html` is left out of v1: no template sends one today; adding it later is a minor.

`pdf-render`: input `{ "html", "filename" }`, output `{ "filename", "base64" }`, the shape BPMN response mappings already read (`S(response).prop('base64')`).

As built (task 1.2, `patchbay/packages/`): `from.name` and `to[].name` are optional (Mailpit's `Name` is), `pdf-render`'s `filename` is required although the renderer defaults it, and the result fields are optional. Patchbay 0.1.0's `required` checks presence only, so `"to": []` passes it and reaches Mailpit, which refuses it with 400 (`no recipients`); no mail is sent, but the refusal comes from the mail system, not from Patchbay. **Decision:** the platform API and the `outbound-integration` spec state the guarantee where it holds (a missing field is refused by Patchbay before any call; an empty `to` sends no mail and is refused by the mail system), and the pack keeps guarding a possibly missing recipient in the BPMN, as it does today for `applicantEmail`. *Rejected: a Patchbay release with a minimum item count before phase 1.* It would gate this change on an upstream release for a case the BPMN already guards and the mail system already refuses; it is an upstream follow-up (Open Questions), and when it ships the guarantee moves into Patchbay with no platform change, since the model is unchanged.

Versioning: adding an optional field is a platform minor; adding a required field, renaming or removing a field, changing its type, or changing a slug is a major. A breaking change to a mapping that keeps the model is a Patchbay version only and needs no platform change.

*Rejected: keep Mailpit's schema and let Patchbay pass it through.* It keeps the vendor schema in 13 pack files, which is the problem this change exists to fix.

### D5. PDF stays on the pdf-renderer

Patchbay maps `pdf-render` one to one onto the renderer's `POST /render`. The renderer is still needed because the http-connector returns the body as a String, and the renderer already turns Gotenberg's binary answer into base64 JSON and carries the Gotenberg deny list and JavaScript-off setting.

*Rejected for now: Patchbay calls Gotenberg directly* with its multipart and blob support (D-29, present in 0.1.0). It would retire a service, but it needs a text field sent as a file part named `index.html` and a binary answer returned as base64 JSON, neither tried, and the renderer's guards would move into Gotenberg flags. Worth its own change once measured.

### D6. Contracts

- Mailpit: `server/ui/api/v1/swagger.json` at tag `v1.31.3` of `axllent/mailpit`, the version compose pins. Checked: the file exists (62,930 bytes, Swagger 2.0 style) and describes `/api/v1/send`.
- pdf-renderer: it has no OpenAPI. A hand-written `pdf-renderer/openapi.json` (OpenAPI 3.0, `POST /render`, the 400 and 502 error shapes from `server.js`) is added to the module, which owns it and changes it with `server.js`.

### D7. Packages live in git unpacked, and are zipped at image build

Core packages live in `patchbay/packages/<nn>-<name>/` (`10-core-mail`, `20-core-pdf`), a pack's in `<pack>/patchbay/<name>/`, each the unpacked content of a Patchbay **Export**: `manifest.json`, `entities/`, `services/<slug>.json`, `services/<slug>.versions/<version>.json`, `contracts/`. They are reviewable JSON in pull requests. A package is changed by building and testing it in a local console, publishing it there, exporting it and unpacking the ZIP over the folder; published version files are never hand-edited, because Patchbay refuses a version that exists with other content.

The image build zips each folder into `/import/<folder>.zip` in a build stage (`zip` on a pinned Alpine), so the import order is the folder names: core `10-`/`20-` before a pack's `50-`. Dev compose and the VM get the same ZIPs from the same Dockerfiles.

*Rejected: commit ZIPs.* Not reviewable, and a ZIP can drift from what its folder says.
*Considered: an upstream change letting `MAPPER_IMPORT_DIR` read unpacked folders.* It would drop the zip stage, but it is not needed for this change; listed under Open Questions.

### D8. Images: a core Patchbay image and a pack layer

- `patchbay/Dockerfile` builds `cib7-poc-patchbay` FROM `krixerx/patchbay:<pin>` (`0.1.0` in phase 1, the sub-path release in phase 3), adds the core ZIPs and sets `MAPPER_IMPORT_DIR=/import`. It holds nothing of a pack, like `cib7-poc-pdf-renderer`.
- Every pack has `docker/patchbay.Dockerfile` FROM that image (build argument `PATCHBAY_CORE`). It has the same zip stage as the core image: each folder `<pack>/patchbay/<name>/` becomes `/import/50-<name>.zip`, so the stage, not the pack author, sets the order after the core's `10-`/`20-`. A pack with no integrations of its own (the reference pack today) keeps the same Dockerfile and an empty `patchbay/` (a `.gitkeep`), so the deploy kit always runs `${PACK_IMAGE_PREFIX}-patchbay:${PACK_TAG}`. The core's CI builds the layer once with a fixture package, so a pack package is proven to load, which the esb's pack routes never were (below).

*Rejected: mount the pack's packages from the host's `pack/`* (the Keycloak precedent). Import reads one folder, so core ZIPs from the image and pack ZIPs from the host would have to meet in one host directory; a mount is not scanned, tagged or checked for its user like a layer; and the dev compose would load packages differently from the VM. The esb precedent shows the risk: `esb.Dockerfile` existed for pack routes, but `deploy/docker-compose.yml` ran the core bus image, so a pack's routes never reached the VM.

### D9. `/config` is not persisted

`/config` is a `tmpfs` owned by uid 10001, overriding the image's `VOLUME`. Every start imports the packages into an empty folder, so each integration goes live with the version that was live in its package, and the login comes from the environment.

Why: with a persistent volume, an existing `activeVersion` is kept on import, so a redeploy carrying a newer version of `mail-send` would leave the old one live without any error. And console edits would survive restarts and drift from git.

*Rejected: a persistent named volume plus a rule to activate the newest version at deploy* through the admin API. It adds a deploy step that must never be skipped, to keep state nobody wants.

Verified (task 1.7) on Docker Engine 29.7.2 and Compose v5.5.0 (local Docker Desktop): `tmpfs: ["/config:uid=10001"]` (short syntax; `docker run --tmpfs /config:uid=10001`) mounts `tmpfs ... mode=755,uid=10001`, Patchbay writes its files there, and after publishing `pdf-render` 1.1.0 through the admin API a `docker restart` comes back with 1.0.0 live and no 1.1.0 file. The `mode=1777` fallback was not needed. The VM's Docker version is untested.

Consequence, written into `docs/architecture.md` and the console section of `docs/deployment.md`: the console is for watching calls, testing and trying a change; a change is kept only by exporting it into git.

### D10. Configuration at compose level

Core, in `docker-compose.yml`, `docker-compose.prod.yml` and `deploy/docker-compose.yml` (connection ids follow the contract names: the exports of task 1.4 use `mailpit` and `pdf-renderer`):

| Variable | Local | Deploy kit |
|---|---|---|
| `MAPPER_URL_MAILPIT` | `http://mailpit:8025` | `http://mailpit:8025/mailpit` (the `MP_WEBROOT`) |
| `MAPPER_URL_PDF_RENDERER` | `http://pdf-renderer:8088` | same |
| `INBOUND_API_KEY` | `${PATCHBAY_API_KEY:-patchbay-api-key-change-me}` | same; must equal the engine's `PATCHBAY_API_KEY` |
| `MAPPER_ADMIN_USER` / `MAPPER_ADMIN_PASSWORD` | `${PATCHBAY_ADMIN_USER:-admin}` / `${PATCHBAY_ADMIN_PASSWORD:-patchbay-admin-change-me}` | same; `deploy.sh` already refuses every `:-...change-me` default left unset in the host's `.env`; it gains a minimum length of 20 characters |
| `MAPPER_CALL_LOG` | `on` | `${PATCHBAY_CALL_LOG:-off}` |
| `MAPPER_OUTBOUNDTIMEOUTMS` | `30000` | same (PDF rendering; measured in task 1.8) |

`BUS_TOKEN` and `MAILPIT_SEND_URL` go away; the engine gains `PATCHBAY_URL`, `PATCHBAY_API_KEY`, `BACKEND_URL` and `INTERNAL_TASK_TOKEN`.

A pack's own variables (`MAPPER_URL_PACK_*`, `MAPPER_HEADER_PACK_*`, `MAPPER_SECRET_PACK_*`) come from a host-local, git-ignored `patchbay-pack.env` beside `.env`, read through `env_file` with `required: false`. The deploy kit names no pack variable. `deploy.sh` refuses the file if it holds a key outside those prefixes or a URL whose host is a core service name (D14).

*Rejected: pack variables listed in `deploy/docker-compose.yml`.* The kit holds nothing of a pack.

### D11. Networks

| Network | Members | Replaces |
|---|---|---|
| `integration` | cib7, patchbay | `bus` |
| `mail` | patchbay, mailpit, mailpit-ui, mailpit-auth | `mail` (esb leaves) |
| `pdf` | patchbay, pdf-renderer | `pdf` (esb and graylog leave) |
| `ingress-patchbay` | traefik, patchbay (deploy kit and prod overlay) | new |
| `gelf-cib7`, `gelf-backend`, `gelf-pdf` | each shipper with graylog | the GELF paths graylog had through `bus`, `esb-backend` and `pdf` |

`esb-backend` is removed; the engine reaches the backend over `app`, which they already share. Graylog leaves every network Patchbay is on, because the console's Test button and a pack integration can call any host Patchbay reaches, and Graylog's API listens on those networks. Patchbay shares no network with the backend, Keycloak, Postgres, RustFS or the log store. It does share `integration` with the engine; the engine's endpoints there need a JWT or the webapp login, and the pack check refuses core hosts anyway.

### D12. The console at `/patchbay/`

Phase 3 only, after an upstream Patchbay release with sub-path support:

- **Upstream (Patchbay repository):** Vite `base: './'`; `api.ts` fetches `admin/api…` relative to the page; the OpenAPI link and the Publish dialog's URL and `curl` stop using `/` and `window.location.origin` as the root (they show the internal address `http://patchbay:8080` or a configured caller base URL instead, since the integration API is not public); audit every server-built absolute URL and redirect. The server keeps matching root paths, because the proxy strips the prefix. Tests: the built `index.html` holds no absolute `/assets/` reference, and the console works when served under a prefix. Release `v0.2.0`.
- **Traefik:** router `patchbay` on the TLS entrypoint, rule `Path(`/patchbay/`) || Path(`/patchbay/index.html`) || Path(`/patchbay/favicon.svg`) || PathPrefix(`/patchbay/assets/`) || PathPrefix(`/patchbay/admin/`)`, middlewares `patchbay-ratelimit` (average 10, burst 50 per IP: the console loads a handful of assets per page), `patchbay-headers` (HSTS, nosniff, no-referrer, permissions policy; no CSP and no frame header of its own, because Patchbay sends `Content-Security-Policy` and `X-Frame-Options: DENY` on every console and admin answer) and a `patchbay-strip` stripPrefix. A second router redirects `Path(`/patchbay`)` to `/patchbay/`. Everything else under `/patchbay` matches no router and gets Traefik's 404, so `/patchbay/api/**` and `/patchbay/actuator/**` stay unreachable.
- **Origin check:** Traefik passes `Host` unchanged, and `AdminRequestFilter.sameOrigin` compares `Origin` with `Host` with default ports left out, so no forwarded-header setting is needed. `SERVER_FORWARD_HEADERS_STRATEGY` stays unset; the phase 3 tests prove a console write behind Traefik.
- **Password guessing:** Patchbay has no lockout. The controls are TLS, the rate limit, a random password of at least 20 characters checked by `deploy.sh`, and Patchbay logging each failed login.

*Rejected: a context path* (`server.servlet.context-path=/patchbay`). It moves `/api/v1/integrations` and the health check too, so the engine's URL and the image's `HEALTHCHECK` would change, and the image would be bound to one public path.
*Rejected: a subdomain.* The user chose the path.
*Rejected for now: the Keycloak gate of `/mailpit` in front.* The user asked for Patchbay's own password, and two logins on one path (oauth2-proxy session and HTTP Basic in the `Authorization` header) is untested. It can be added later without changing Patchbay.

### D13. Logs: stdout, with a named exemption

Patchbay writes plain stdout, one line per call. `docker compose logs patchbay` with the json-file driver's rotation (`max-size`, `max-file`) is the answer; `docs/logging.md` names Patchbay as the one service not in Graylog, and `docs/security.md` lists it with the demo exemptions.

*Rejected: Docker's `gelf` log driver.* The daemon sends from the host, so Graylog's GELF input would have to be published on the host, which `docs/logging.md` rules out, and the lines would still lack the `level`/`user_id` contract. GELF in Patchbay itself is an upstream follow-up.

`MAPPER_CALL_LOG` is `off` on the VM: the call log keeps the last 100 payloads per integration in memory, which on this stack means names, email addresses, capability links and whole PDFs in base64, readable by anyone with the console password. It is `on` locally, where it is the point of the console.

### D14. The pack boundary, checked

`PackBusTest` is replaced by `PackIntegrationTest` (tagged `pack`), with the rules in `specs/pack-integration-boundary/spec.md`:

- connector URLs: `${patchbayBaseUrl}/api/v1/integrations/<slug>` for `mail-send`, `pdf-render` or a slug the pack's packages declare; `${backendBaseUrl}` with the same fixed paths and registry rule as today; anything else, `${busBaseUrl}` included, refused;
- no connector header `X-Internal-Token` or `X-API-Key`;
- in `<pack>/patchbay/*/`: a valid manifest; every slug, definition id, contract id and connection id starts with `pack-`; no `connections/` or `secrets/`; no `target.baseUrl` in any draft or version.

At deploy, `deploy.sh` checks `patchbay-pack.env` (D10). At run time, the network map (D11) makes a core host unreachable even if both checks were bypassed.

### D15. Platform API 3.0

`PLATFORM_MAJOR = 3`, `PLATFORM_MINOR = 0` in both `PackManifest` classes; `docs/platform-api.md` says `3.0` with a paragraph on what broke; `packs/reference/pack.yaml` says `"3.0"`; every backend descriptor (`consent/*.yaml`, `payment/*.yaml`, `registry/*.yaml`, `documents.json`) says `platform: 3`, because the backend compares that number with `PLATFORM_MAJOR` (`DocumentCategories`, `ConsentCatalog`, `FeeCatalog`). The pack layout gains `patchbay/` and `docker/patchbay.Dockerfile` and loses `esb/routes/pack-<name>.yaml` and `esb.Dockerfile`. The service-builder skill and its spec template change in the same commit.

### D16. Phases

1. **Core swap on Patchbay 0.1.0** (this repository only): engine, packages, images, compose, pack regeneration, docs. Patchbay is reachable locally on `127.0.0.1:${PATCHBAY_HTTP_PORT:-8090}` and on the VM only through an SSH tunnel to the same loopback binding (rule D7).
2. **Upstream Patchbay** sub-path release, in the Patchbay repository.
3. **Public console**: pin the new release, Traefik routes, smoke tests.

Phase 1 can ship alone; phase 3 waits for phase 2.

### D17. Trust model for a pack's BPMN and templates

Decided by the user on 2026-11-26, after D2's bean restriction proved partial. **A pack is reviewed code with the engine's power**, and the change guarantees only what it checks:

- *At run time,* expressions and templates resolve no Spring bean but the reserved names (task 2.4, `BeanExposureTest`), and the payload templates' FreeMarker refuses `?new` and `?api`, with the settings `DocumentRenderer` already uses for `engine/documents/` (task 2.6).
- *In the pack check,* `PackIntegrationTest` (task 4.1) refuses reflection-like expressions in connector inputs and outputs and in templates and documents (`getClass`, `forName`, `execution.processEngine…`, `?new`, `?api`). This gates the reference pack, today the only pack, because `docker-publish.yml` builds its layers only after the `quality` job runs the pack tests.
- *Not guaranteed:* a pack image built without those checks can still reach the engine configuration and the process environment through JUEL. `docs/security.md` says so (task 7.3), and pull requests touching a pack are reviewed like core code.

Follow-up, not in this change: a JUEL resolver at the front of the chain that refuses method calls on `Class`, `ClassLoader` and `java.lang.reflect` types and navigation to `processEngine`, `processEngineServices` and `processEngineConfiguration`, before a second pack is added or any pack is built outside this repository's CI.

*Rejected: the JUEL guard in this change.* It is a blocklist of types on CIB seven internals that nobody has measured, and `execution` stays a rich object, so it would delay phase 1 for a guard the CI gate already covers for the one pack there is. *Rejected: the static check alone, claimed as a sandbox.* It holds only for images built after the pack tests, so the docs would promise more than a hand-built image has.

## Risks / Trade-offs

- [Cases in flight still run BPMN versions that read `${busBaseUrl}`; after the bean is gone their next connector fails with an incident] → migrate running instances to the new process versions at deploy (activity ids are unchanged, so a mapping by id works), and list what cannot migrate. On the demo VM, wiping the cases is the accepted fallback.
- [Patchbay is a young product (one release, same-day as this design) in the path of every mail and PDF] → pin the version; images per commit allow a redeploy of the previous SHA; the end-to-end mail and PDF case runs in the tasks before merge.
- [Base URL with a path (`/mailpit`) may not join with the operation path as expected] → a task checks it against Mailpit; fallback is a Mailpit webroot of `/` with mailpit-auth rewriting the path.
- [The public console can change every integration and run real calls] → admin login over TLS, rate limit, long password, Graylog off its networks, call log off, ephemeral config so any change is undone by a restart.
- [`tmpfs` ownership for uid 10001 behaves differently across Docker versions] → a task verifies Patchbay can write `/config` on the VM's Docker version; fallback is `mode=1777`.
- [The engine now holds `INTERNAL_TASK_TOKEN`] → see D2; interceptor tests prove where it goes.
- [The core Patchbay image is built FROM the upstream image, and `docker-publish.yml` refuses to push an image with a fixable HIGH or CRITICAL finding, with no ignores allowed; an upstream finding would block every deploy] → scan `krixerx/patchbay:0.1.0` with Trivy before phase 1 (task 1.8); a finding is fixed by an upstream release, which this project controls.
- [Patchbay's outbound timeout defaults to 10 s for every integration; a long certificate render would answer 504 and retry as an incident] → set `MAPPER_OUTBOUNDTIMEOUTMS` to 30000 in compose and measure the slowest render (task 1.8).
- [Large attachments in JSON] → mail with a PDF attachment stays within `MAPPER_MAXBODYBYTES` (10 MiB); today's invoices are far below that. A larger document needs the limit raised in compose.

## Migration Plan

1. Merge phase 1 as one commit: engine, backend major, pack regenerated, `patchbay/`, compose, workflows, docs, `esb/` removed.
2. Write `PATCHBAY_API_KEY` and `PATCHBAY_ADMIN_PASSWORD` into the host's `.env` by hand (the workflow never writes secrets there), then deploy with the Deploy to VM workflow. `deploy.sh` refuses to start while either is unset, because both have `-change-me` defaults in the kit. `BUS_TOKEN` stays in `.env` until rollback is no longer needed.
3. Migrate running process instances (or wipe the demo cases), then run the smoke tests and one vehicle and one business case end to end (mail in Mailpit, certificate and invoice PDFs stored).
4. Phase 3 after the Patchbay release: pin it, copy the new `routes.yml.example` blocks into the host's `routes.yml`, redeploy, smoke-test the gate.

**Rollback:** run the Deploy to VM workflow with the previous commit's SHA. Its images (`cib7-poc-esb` included) are still on Docker Hub under that SHA, and its deploy kit brings the esb service and its `.env` keys back (keep `BUS_TOKEN` in the host's `.env` until phase 1 has run for a while). Cases started on the new BPMN cannot run on the old engine; on the demo VM they are wiped.

**Docs updated in the same change:** `CLAUDE.md` (architecture diagram, module responsibilities, platform API paragraph), `README.md`, `AGENTS.md`, `docs/platform-api.md`, `architecture.md` (topology, networks, the new rule), `cib7.md`, `security.md` (rules 5, 8, 9, 11, demo exemptions), `logging.md`, `deployment.md`, `ci-cd.md`, `deploy/README.md`, the service-builder skill and spec template, `deployment-conventions` (D6 rewritten), `backend-conventions`, the `esb-conventions` skill and `.claude/rules/esb.md` replaced by a short `patchbay` rule and conventions, and the agent context files.

## Open Questions

- Should Patchbay's `MAPPER_IMPORT_DIR` read unpacked package folders (upstream), so the zip build stage goes away? Not needed for this change.
- GELF support in Patchbay itself, so it joins Graylog with the field contract.
- Retiring the pdf-renderer for a direct Gotenberg integration (D5), measured in its own change.
- A Keycloak gate in front of `/patchbay/` in addition to the Basic login, if the console should be limited to realm users.
- A minimum item count for lists in Patchbay's payload definitions (upstream), so `mail-send` refuses `"to": []` itself (D4).
- How the Deploy to VM workflow forwards a pack's Patchbay secrets (rule D14) once a pack has an integration of its own; the reference pack has none, so nothing needs forwarding yet.
