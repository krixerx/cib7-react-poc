# MCP sidecar module (`mcp/`)

**When to read this:** before editing anything under `mcp/`; when changing
the MCP tool catalog, the engine forwarding logic, the OAuth wiring, or
the per-service manifest format; when wiring a different MCP host (Cursor,
Codex, Windsurf) against the deployment.

This module is the **standalone Model Context Protocol (MCP) microservice**
that exposes the CIB seven deployment as an AI-callable surface. Claude
Desktop (or any MCP-capable client) connects to `/mcp/sse`, completes an
OAuth2 PKCE-loopback flow against Keycloak, and drives the deployment
through eight MCP tools. The sidecar is a **stateless Bearer-proxy** in
front of `/engine-rest` — it never validates JWTs locally, never holds
refresh tokens server-side, and never persists user state. The engine
remains the security boundary.

**Contents**
1. [Stack](#stack)
2. [Architecture choice — why a sidecar, not an engine plugin](#architecture-choice--why-a-sidecar-not-an-engine-plugin)
3. [File layout](#file-layout)
4. [The eight tools](#the-eight-tools)
5. [Auth — OAuth2 PKCE-loopback, step by step](#auth--oauth2-pkce-loopback-step-by-step)
6. [Manifest loading + per-service contracts](#manifest-loading--per-service-contracts)
7. [Discovery surface (`.well-known`, `<meta>` tags)](#discovery-surface-well-known-meta-tags)
8. [Configuration surface (env vars)](#configuration-surface-env-vars)
9. [Run, build, package](#run-build-package)
10. [Conventions and extensions](#conventions-and-extensions)

---

## Stack

| | |
|---|---|
| Language | TypeScript 5.5 (strict) |
| Runtime | Node 20, run directly via `tsx` (no `tsc` build step) |
| Framework | Express 4 |
| MCP SDK | `@modelcontextprotocol/sdk` 1.18+ (Streamable HTTP transport) |
| Schema validator | Ajv 8 + ajv-formats (JSON Schema draft 2020-12) |
| Container | `node:20-alpine`, single-stage Dockerfile |
| Build context | Repo root (so the Dockerfile can COPY both `mcp/` and `docs/business/services/`) |
| Image footprint | ~120 MB (Alpine base + node_modules) |
| Health probe | `GET /health` returning `{ ok, service, version, manifests: [...] }` |

## Architecture choice — why a sidecar, not an engine plugin

The MCP server lives **outside** the engine in its own Node container,
calling `/engine-rest` like any other HTTP client. There's a separate
[`krixerx/cibseven-mcp-plugin`](https://github.com/krixerx/cibseven-mcp-plugin)
that embeds an MCP server inside the engine JVM — a legitimate
alternative, useful when:

- The deployment can't add containers (embedded shipping, restricted
  hosting).
- The MCP server needs in-engine APIs that aren't on `/engine-rest`
  (`RuntimeService.signalEventReceived`, `ManagementService.executeJob`,
  raw history tables).
- Operational MCP tools (incident retry, job rescheduling, log access)
  are the primary use case.

This POC chose the sidecar pattern because:

1. **Composable.** The same sidecar can wrap a future `document-signer`
   or `payment-gateway` microservice without coupling them to the engine.
2. **Best-in-class tooling.** The TypeScript MCP SDK is the most mature
   MCP SDK in 2026; Java MCP support lags by a year.
3. **Independent release cycle.** Engine upgrades don't drag MCP changes
   along, and vice versa.
4. **Mirrors `pdf-renderer/`.** The "JSON-in/JSON-out sidecar" pattern is
   already established in this repo for the same reasons.
5. **Cleaner JWT story.** Bearer forwarding to `/engine-rest` reuses the
   engine's existing `RestApiSecurityConfig` validation — no in-engine
   identity-binding magic required.

The architectural decisions are captured in the [office-hours design
doc](../README.md#add-or-modify-a-service) and the eng-review report
appended to the same file. The load-bearing decisions:

- **A1** — Audience claim wired via a dedicated client scope
  (`cib7-rest-api-audience`) on `cib7-mcp`, not an inline mapper.
- **A2** — Stateless Bearer-proxy (Model A): Claude Desktop owns the
  OAuth lifecycle end-to-end; the sidecar never holds refresh tokens.
- **A3** — Seed history uses OAuth2 Resource Owner Password Credentials
  (ROPC) to authenticate as `bart` for autofill demo prep.
- **Q1** — JSON Schema draft 2020-12 + Ajv as the manifest schema language.

## File layout

```
mcp/
├── package.json                # @modelcontextprotocol/sdk, express, tsx, ajv, ajv-formats
├── tsconfig.json               # strict, noEmit (tsx runs source)
├── Dockerfile                  # node:20-alpine, COPYs from repo root
└── src/
    ├── server.ts               # Express + MCP transport + tool registry
    ├── auth/
    │   └── identity.ts         # decodeBearerUsername (no signature check — engine validates)
    ├── engine/
    │   ├── client.ts           # Bearer-forward fetch wrapper; { ok, status, code, message, retryable, data }
    │   └── variables.ts        # plain JSON → Camunda { value, type } envelope, schema-driven
    └── services/
        └── manifest.ts         # walks /app/services-spec, Ajv-compiles every schema, indexes by formKey
```

`/app/services-spec` is populated at image build time by the Dockerfile's
`COPY docs/business/services /app/services-spec` directive. The mcp
container ships with whatever's in the repo at build time — to add a new
service or update one, run `/service-builder` and rebuild the image.

## The eight tools

All return `{ ok: true, data }` on success or `{ ok: false, status, code,
message, retryable }` on failure. Schema-validation failures surface as
`{ code: 'INVALID_VARIABLES', issues: [...] }` (Ajv issues array) without
hitting `/engine-rest`. Engine 401 surfaces as `{ code: 'engine_unauthorized' }`
— Claude Desktop's MCP client refreshes its token and retries on the next
call (decision A2). 5xx surfaces as `{ retryable: true }`.

| Tool | Engine endpoint | Purpose |
|---|---|---|
| `list_services` | `GET /engine-rest/process-definition?latestVersion=true` | Deployed definitions decorated with `mcpCallable` flag from the manifest registry. |
| `describe_service(key)` | (no engine call — reads in-memory manifest) | Returns the JSON Schema for `start_process` variables + the LLM training markdown. |
| `start_process(key, variables)` | `POST /engine-rest/process-definition/key/<k>/start` | Ajv-validates → maps to Camunda `{value, type}` → engine. |
| `list_my_tasks` | `GET /task?assignee=<me>` + `GET /task?candidateUser=<me>&unassigned=true` (merged, deduped) | Returns tasks the user can act on; `action: 'complete' \| 'claim_then_complete'`. |
| `get_form_schema(taskId)` | `GET /task/<id>` then look up by formKey | Returns the per-task JSON Schema + audience + description. |
| `complete_task(taskId, variables)` | (auto-claim if needed) + `POST /task/<id>/complete` | Ajv-validates against per-task schema → engine. Auto-claims candidate-group tasks. |
| `list_my_processes(processInstanceId?)` | `GET /history/process-instance?startedBy=<me>&sortBy=startTime&sortOrder=desc` | Decorated with state (ACTIVE / COMPLETED / ...). |
| `query_user_history(variableName)` | Two-step: instances → variable-instance with `processInstanceIdIn` | Most recent value the user ever entered for that variable. Used for autofill (decision A3 / T15). |

The username `<me>` is decoded from the Bearer's `preferred_username` claim
locally (no signature check; that's the engine's job on the forwarded
call). See `mcp/src/auth/identity.ts`.

## Auth — OAuth2 PKCE-loopback, step by step

The MCP server is unauthenticated for its discovery endpoints
(`/.well-known/oauth-protected-resource`, `/health`) and Bearer-gated on
`/mcp`. Claude Desktop's MCP client handles the full OAuth flow on its
side; the sidecar is just a 401 challenger and a Bearer proxy.

```
1. Claude Desktop loads its MCP config (claude_desktop_config.json) and reaches /mcp/sse.
2. mcp sidecar replies 401 + WWW-Authenticate: Bearer resource_metadata="<url>"
3. Claude Desktop fetches /.well-known/oauth-protected-resource from the sidecar.
   Sidecar returns { resource, authorization_servers: [keycloak issuer], bearer_methods_supported, scopes_supported }.
4. Claude Desktop fetches Keycloak's /.well-known/openid-configuration to learn auth endpoints + PKCE support.
5. Claude Desktop initiates OAuth2 Authorization Code + PKCE with a loopback redirect:
     http://127.0.0.1:<random-port>/callback?code=<auth-code>
6. Browser pops; user logs in to Keycloak; redirect back to the loopback URL.
7. Claude Desktop exchanges code → access_token + refresh_token. The access token carries
   aud=cib7-rest-api (added by the cib7-rest-api-audience client scope on cib7-mcp, T2).
8. Claude Desktop attaches Authorization: Bearer <token> on every subsequent /mcp call.
9. mcp sidecar forwards the same token to /engine-rest. RestApiSecurityConfig validates
   issuer + audience + signature; KeycloakAuthenticationFilter binds the user into
   IdentityService; engine enforces authorization.
10. On engine 401 (token expired mid-conversation), the sidecar returns 401 to Claude
    Desktop verbatim — Claude refreshes via its own OAuth machinery and retries.
```

Three Keycloak realm artifacts make this work
(see [`keycloak/realm-export.json`](../keycloak/realm-export.json)):

- **Client `cib7-mcp`** — public client, PKCE required, loopback redirect
  wildcard (`http://127.0.0.1/*`, `http://localhost/*`).
- **Client scope `cib7-rest-api-audience`** — single Audience mapper that
  adds `cib7-rest-api` to the `aud` claim. Assigned as a default scope on
  `cib7-mcp` so every token carries the right audience.
- **Direct access grants** — enabled on `cib7-mcp` so the seed history
  script (T4 / future polish) can use ROPC. Documented but not currently
  exercised in the live demo.

**MCP host caveat:** the `client_id` Claude Desktop uses depends on its
release. Either it supports Dynamic Client Registration (DCR) and creates
its own client at runtime, or it pins `cib7-mcp` via its MCP server
config. The realm is wired for the pinned-client pattern; if a future
Claude Desktop drops the `client_id` config field, swap to DCR by adding
a `clientRegistrationPolicies` block to the realm export.

## Manifest loading + per-service contracts

At startup the sidecar walks `/app/services-spec/*/build/` for each
service folder and loads two files:

- `mcp-service.json` — manifest with JSON Schema (start_process variables)
  + `userTasks[]` with per-task schemas (complete_task variables).
- `mcp-training.md` — LLM-readable prose exposed as the MCP
  `service_guide` prompt.

Both are generated by [`/service-builder`](../.claude/skills/service-builder/SKILL.md)
from the analyst-authored spec (§ 11 of the skill explains the derivation
rules). The contract between the analyst spec and the MCP sidecar is the
manifest format, not the BPMN itself.

The aggregated index at `docs/business/services/build/services.json` is
also generated by `/service-builder` and lists every MCP-callable service
with its `key`, `name`, `description`, `audience`, and `manifestPath`.

```
docs/business/services/
├── business-registration/
│   ├── README.md                          analyst spec
│   ├── forms/business-details.md          form spec
│   ├── ...
│   └── build/                             generated by /service-builder
│       ├── mcp-service.json               loaded by mcp/src/services/manifest.ts
│       └── mcp-training.md                exposed via the MCP prompt API
├── person-registration/
│   └── ... (same shape)
└── build/
    └── services.json                      aggregated index
```

To add MCP support for a new service:

1. Author the spec in `docs/business/services/<service>/`.
2. Run `/service-builder` — it emits the BPMN + DMN + React forms + Mcp
   manifest + training md + updates the aggregated index.
3. `docker compose build mcp` (the Dockerfile COPYs the new manifest).
4. `docker compose up mcp` — the loader picks up the new service.

That's it. No code change in `mcp/` for a new service.

## Discovery surface (`.well-known`, `<meta>` tags)

The deployment advertises its MCP support in three layers so visiting AI
agents (or developers reading the SPA) can discover it without prior
configuration:

| Layer | Where | What it carries |
|---|---|---|
| `<meta>` tags in `<head>` | `frontend/index.html` | `mcp-server="/mcp/sse"`, `mcp-transport="sse"` |
| Server-level metadata | `/.well-known/oauth-protected-resource` (proxied to `mcp:8090`) | OAuth2 protected-resource metadata (RFC 9728) — points to Keycloak as the authorization server. |
| Per-service catalog | `/.well-known/mcp/services.json` (proxied to `mcp:8090` — see "follow-up" below) | The aggregated services index (auto-generated by `/service-builder`). |

**Follow-up wiring** — the `/.well-known/mcp/services.json` endpoint is
not yet served by nginx at the time of writing. The file exists at
`docs/business/services/build/services.json` and the mcp container has
it at `/app/services-spec/build/services.json`, but there's no
`location /.well-known/mcp/services.json {...}` block in
`frontend/nginx.conf` yet. Adding it is ~5 lines and the missing piece for
true headless-agent discovery. Tracked as a follow-up to T13/T14.

## Configuration surface (env vars)

| Env var | Where | Default | Purpose |
|---|---|---|---|
| `PORT` | `mcp/src/server.ts` | `8090` | HTTP port the sidecar listens on. |
| `MCP_RESOURCE_URL` | `mcp/src/server.ts` | `http://localhost:3000/mcp` | Browser-visible MCP URL (via nginx). Stamped into the OAuth resource metadata. |
| `KEYCLOAK_ISSUER_URL` | `mcp/src/server.ts` | `http://localhost:8180/realms/cib7-poc` | Browser-visible Keycloak realm URL. Must match `KC_HOSTNAME_URL` on the keycloak container so the `iss` claim Claude sees matches what Keycloak stamps into tokens. |
| `ENGINE_URL` | `mcp/src/engine/client.ts` | `http://cib7:8080` | Internal `/engine-rest` base URL (docker-network alias). |
| `SERVICES_SPEC_DIR` | `mcp/src/services/manifest.ts` | `/app/services-spec` | Where the manifest loader looks for `*/build/mcp-service.json`. |

Configured per-service in `docker-compose.yml` under the `mcp` service.
Internal-vs-browser URL split follows the same pattern as the
[`cib7` service](architecture.md#deployment-topology): Claude Desktop sees
the `localhost` URLs through nginx; the sidecar reaches the engine on the
docker-network alias.

## Run, build, package

```bash
# Build + run as part of the full compose
docker compose up --build

# Build the mcp image only (after editing manifests or sidecar code)
docker compose build mcp
docker compose up mcp

# Inspect the deployed manifests inside the running container
docker exec cib7-poc-mcp ls /app/services-spec
docker exec cib7-poc-mcp wget -qO- http://127.0.0.1:8090/health

# Verify the OAuth resource metadata
curl http://localhost:3000/.well-known/oauth-protected-resource
```

For local dev without Docker:

```bash
cd mcp
npm install
PORT=8090 \
  ENGINE_URL=http://localhost:8080 \
  KEYCLOAK_ISSUER_URL=http://localhost:8180/realms/cib7-poc \
  MCP_RESOURCE_URL=http://localhost:3000/mcp \
  SERVICES_SPEC_DIR=../docs/business/services \
  npm start
```

The container exposes only `8090` internally; nginx is the public face on
port 3000. There's no host port mapping on `mcp` so probing it from the
host requires going through nginx or `docker exec`.

## Conventions and extensions

- **Style.** TypeScript strict mode, ES modules, `node:` prefix on
  built-in imports. Match the existing files (`engine/client.ts`,
  `auth/identity.ts`) for shape and comment tone.
- **No silent error swallowing.** Every tool handler returns a typed
  envelope; engine failures map to specific `code` values; never throw
  out of a tool handler.
- **Schema-first changes.** Adding a new tool means: (1) define the input
  schema in `ListToolsRequestSchema` response; (2) implement the handler
  with the same envelope shape; (3) add the engine endpoint to the table
  in this doc. The LLM sees only what the schema says.
- **No persistence.** The sidecar holds no per-user state. The MCP
  protocol is stateless from the sidecar's view — every tool call carries
  its own Bearer. Long-running session state (chat memory, transcript)
  is Claude Desktop's responsibility.
- **JWT decoded, not verified.** `auth/identity.ts` reads
  `preferred_username` for query construction (`assignee=<me>`,
  `startedBy=<me>`). The engine validates the token signature on every
  forwarded call — the sidecar trusts the engine to reject bad tokens.
- **Adding a new MCP tool that doesn't wrap `/engine-rest`** (e.g., a
  tool that calls `pdf-renderer` directly, or a future
  `document-signer` microservice) is fine — add a new `mcp/src/<area>/client.ts`
  wrapper alongside `engine/client.ts` and register the tool in
  `server.ts`. The sidecar's "wrap any backend" composability is the
  rationale for the sidecar pattern.

## Related docs

- [`docs/architecture.md`](architecture.md) — system overview; where the
  mcp container fits in the deployment topology.
- [`docs/cib7.md`](cib7.md) — engine module; auth wiring on the engine
  side (issuer + audience validation, `KeycloakAuthenticationFilter`).
- [`docs/frontend.md`](frontend.md) — SPA module; the `<meta>` tags
  pattern and `/mcp` nginx proxy.
- [`mcp/README.md`](../mcp/README.md) — quick-start and Claude Desktop
  configuration; verify steps for each task gate (T1, T2, T3, T6, T9).
- [`.claude/skills/service-builder/SKILL.md`](../.claude/skills/service-builder/SKILL.md)
  § 11 — how the per-service manifests + training markdown get generated
  from the analyst spec.
- [`krixerx/cibseven-mcp-plugin`](https://github.com/krixerx/cibseven-mcp-plugin)
  — the in-engine MCP plugin alternative (Java, Spring Boot starter).
  Useful as a comparison architecture.
