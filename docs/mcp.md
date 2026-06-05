# MCP sidecar module (`mcp/`)

**When to read this:** before editing anything under `mcp/`; when changing
the MCP tool catalog, the engine forwarding logic, the OAuth wiring, or
the per-service manifest format; when wiring a different MCP host (Cursor,
Codex, Windsurf) against the deployment.

This module is the **standalone Model Context Protocol (MCP) microservice**
that exposes the CIB seven deployment as an AI-callable surface. Claude
Desktop (or any MCP-capable client) connects to `/mcp`, completes an
OAuth2 PKCE-loopback flow against Keycloak, and drives the deployment
through **eleven MCP tools** — eight process tools wrapping `/engine-rest`
plus three identity tools (sign-up URL lookup, password-reset URL lookup,
and an email-invitation flow that creates an invite-pending Keycloak user
without ever handling a password). The sidecar is a **Bearer-proxy** in
front of `/engine-rest` — it forwards the caller's token unchanged on
every process call. It does verify the JWT signature against Keycloak's
JWKS at the door so stale tokens surface as a clean HTTP 401 and trigger
the MCP client's re-auth, but it never holds refresh tokens server-side
and never persists user state. The engine remains the authoritative
security boundary (issuer + audience + signature + per-user authorization
on every forwarded call).

**Contents**
1. [Stack](#stack)
2. [Architecture choice — why a sidecar, not an engine plugin](#architecture-choice--why-a-sidecar-not-an-engine-plugin)
3. [File layout](#file-layout)
4. [The eleven tools](#the-eleven-tools)
5. [Auth — OAuth2 PKCE-loopback, step by step](#auth--oauth2-pkce-loopback-step-by-step)
6. [Connecting Claude Desktop (Windows quirks)](#connecting-claude-desktop-windows-quirks)
7. [User registration and onboarding](#user-registration-and-onboarding)
8. [Manifest loading + per-service contracts](#manifest-loading--per-service-contracts)
9. [Discovery surface (`.well-known`, `<meta>` tags)](#discovery-surface-well-known-meta-tags)
10. [Configuration surface (env vars)](#configuration-surface-env-vars)
11. [Run, build, package](#run-build-package)
12. [Conventions and extensions](#conventions-and-extensions)

---

## Stack

| | |
|---|---|
| Language | TypeScript 5.5 (strict) |
| Runtime | Node 20, run directly via `tsx` (no `tsc` build step) |
| Framework | Express 4 |
| MCP SDK | `@modelcontextprotocol/sdk` 1.18+ (Streamable HTTP transport, per-request server) |
| Schema validator | Ajv 8 + ajv-formats (JSON Schema draft 2020-12) |
| JWT verification | `jose` 5 against Keycloak's JWKS (signature + issuer; audience is the engine's job) |
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
├── package.json                # @modelcontextprotocol/sdk, express, tsx, ajv, ajv-formats, jose
├── tsconfig.json               # strict, noEmit (tsx runs source)
├── Dockerfile                  # node:20-alpine, COPYs from repo root
├── cib7-bridge.mjs             # stdio↔HTTP bridge for Claude Desktop (see "Connecting Claude Desktop" below)
└── src/
    ├── server.ts               # Express + per-request MCP server/transport + tool registry + LLM instructions
    ├── auth/
    │   ├── identity.ts         # decodeBearerUsername (parse only — engine validates)
    │   └── verify.ts           # jwtVerify against Keycloak JWKS — 401 + WWW-Authenticate on stale tokens
    ├── engine/
    │   ├── client.ts           # Bearer-forward fetch wrapper; { ok, status, code, message, retryable, data }
    │   └── variables.ts        # plain JSON → Camunda { value, type } envelope, schema-driven
    ├── keycloak/
    │   └── admin.ts            # cib7-backend service-account token + admin REST wrapper (used by send_account_invitation)
    └── services/
        └── manifest.ts         # walks /app/services-spec, Ajv-compiles every schema, indexes by formKey
```

`/app/services-spec` is populated at image build time by the Dockerfile's
`COPY docs/business/services /app/services-spec` directive. The mcp
container ships with whatever's in the repo at build time — to add a new
service or update one, run `/service-builder` and rebuild the image.

## The eleven tools

All return `{ ok: true, data }` on success or `{ ok: false, status, code,
message, retryable }` on failure. Schema-validation failures surface as
`{ code: 'INVALID_VARIABLES', issues: [...] }` (Ajv issues array) without
hitting `/engine-rest`. Stale tokens are caught at the `/mcp` door by JWT
verification and return HTTP 401 + `WWW-Authenticate: Bearer error="invalid_token"`,
which mcp-remote treats as "re-run OAuth" automatically. Engine 5xx
surfaces as `{ retryable: true }`.

**Process tools** (forward the caller's Bearer to `/engine-rest`):

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

**Identity tools** (Keycloak instead of the engine — see [User registration and onboarding](#user-registration-and-onboarding)):

| Tool | Keycloak endpoint | Purpose |
|---|---|---|
| `get_signup_url` | (none — builds the URL locally) | Returns the public hosted Keycloak registration URL plus the steps to relay to the user. Pure URL lookup; performs no action. |
| `get_password_reset_url` | (none — builds the URL locally) | Returns the public hosted Keycloak `kc_action=reset_credentials` URL plus the steps. Pure URL lookup. |
| `send_account_invitation(username, email, firstName, lastName)` | `POST /admin/realms/<r>/users` + `PUT /admin/realms/<r>/users/<id>/execute-actions-email` | Creates an invite-pending Keycloak user with `requiredActions: ["UPDATE_PASSWORD","VERIFY_EMAIL"]`, then triggers the magic-link email. The invitee sets their own password in Keycloak — the tool never accepts or returns one. Uses the `cib7-backend` service-account client (client_credentials grant), not the caller's Bearer. |

The username `<me>` (for process tools) is decoded from the Bearer's
`preferred_username` claim locally (parse only — `verifyBearer` already
ran at the door). See `mcp/src/auth/identity.ts`.

The tool surface is also wrapped with an MCP **`instructions`** field
returned in the initialize handshake. It tells the LLM how to handle
"I'm new", "I forgot my password", and "register someone" requests —
specifically, to prefer `send_account_invitation` over a static link
when an email is on hand, and to NEVER ask the user for a password in
chat. See the `SERVER_INSTRUCTIONS` constant at the top of `server.ts`.

## Auth — OAuth2 PKCE-loopback, step by step

The MCP server is unauthenticated for its discovery endpoints
(`/.well-known/oauth-protected-resource`, `/health`) and Bearer-gated on
`/mcp`. The MCP client (Claude Desktop, possibly via the `mcp-remote`
stdio bridge — see next section) handles the full OAuth flow; the sidecar
challenges, verifies JWTs at the door, then forwards them.

```
1. MCP client POSTs /mcp. No Bearer (or stale one) → 401 +
   WWW-Authenticate: Bearer resource_metadata="<url>", error="invalid_token".
2. MCP client fetches /.well-known/oauth-protected-resource from the sidecar.
   Sidecar returns { resource, authorization_servers: [keycloak issuer],
   bearer_methods_supported, scopes_supported: ["openid"] }.
3. MCP client fetches Keycloak's /.well-known/openid-configuration.
4. MCP client initiates OAuth2 Authorization Code + PKCE with a loopback
   redirect: http://127.0.0.1:<random-port>/oauth/callback?code=<auth-code>
   The request uses client_id=cib7-mcp and scope=openid only.
5. Browser pops to Keycloak's login page. The page includes "Register" and
   "Forgot Password?" links (realm flags registrationAllowed=true,
   resetPasswordAllowed=true). The user can register inline if they don't
   have an account; verifyEmail=true means a verification email arrives at
   Mailpit (smtpServer.host=mailpit, port=1025) and the user clicks the
   link before the session activates.
6. After successful login Keycloak redirects to the loopback URL with the
   auth code. mcp-remote exchanges code → access_token + refresh_token.
   The token carries: preferred_username + realm_access.roles (cib7-claims
   scope) and aud=cib7-rest-api (cib7-rest-api-audience scope).
7. MCP client attaches Authorization: Bearer <token> on every subsequent
   /mcp call.
8. mcp sidecar's requireBearer middleware verifies the JWT against
   Keycloak's JWKS (signature + issuer). On signature mismatch or expiry
   it returns HTTP 401 + WWW-Authenticate, which mcp-remote treats as a
   re-auth signal — fresh OAuth dance, browser pops again.
9. On verification success the sidecar forwards the same token to
   /engine-rest. RestApiSecurityConfig validates issuer + audience +
   signature again; KeycloakAuthenticationFilter binds the user into
   IdentityService; engine enforces per-user authorization.
```

**Why scope is just `openid`.** The realm export doesn't define the
built-in `profile` / `email` client scopes (Keycloak's realm import
treats the `clientScopes` array as authoritative — anything you don't
list doesn't exist). The sidecar's `.well-known/oauth-protected-resource`
advertises `scopes_supported: ["openid"]` so mcp-remote requests only that.
The claims the engine actually needs (`preferred_username`,
`realm_access.roles`, `aud=cib7-rest-api`) ride on the realm-default
scopes `cib7-claims` and `cib7-rest-api-audience`, not on `profile`/`email`.

**Keycloak realm artifacts** that make all of this work
(see [`keycloak/realm-export.json`](../keycloak/realm-export.json)):

- **Client `cib7-mcp`** — public client, PKCE required, loopback redirect
  wildcard (`http://127.0.0.1/*`, `http://localhost/*`). Default scopes:
  `cib7-claims`, `cib7-rest-api-audience`.
- **Client `cib7-frontend`** — public SPA client, PKCE required. Default
  scope: `cib7-claims`. Carries the audience mapper inline (legacy from
  before `cib7-rest-api-audience` was extracted).
- **Client scope `cib7-claims`** — custom scope with two protocol mappers:
  `preferred_username` (oidc-usermodel-property) and `realm_access.roles`
  (oidc-usermodel-realm-role). Replaces the missing built-in `profile`
  and `roles` scopes. Assigned as a realm-default-default and on every
  user-facing client.
- **Client scope `cib7-rest-api-audience`** — single Audience mapper that
  adds `cib7-rest-api` to the `aud` claim.
- **Client `cib7-backend`** — confidential service-account client with
  `realm-management` roles: `query-users`, `view-users`, `query-groups`,
  `query-clients`, `view-clients`, **`manage-users`**. Used by
  `send_account_invitation` to create invite-pending users via admin REST.
- **Realm `frontendUrl`** attribute pinned to `http://localhost:8180` —
  forces every generated link (OIDC issuer, action-token emails) to the
  public URL regardless of which container called the admin API. Without
  this, invitation emails triggered from the MCP container would contain
  `http://keycloak:8080/...` links the browser can't reach.
- **Realm flags** — `registrationAllowed`, `resetPasswordAllowed`,
  `verifyEmail` all `true`; `defaultGroups: ["/applicant"]` so any
  self-registered or invited user lands in the applicant group; SMTP
  pointed at `mailpit:1025`.

**MCP host caveat:** the `client_id` the MCP client uses depends on its
release. Claude Desktop on Windows currently goes through the
`mcp-remote` stdio bridge (next section). The bridge defaults to Dynamic
Client Registration (DCR), but Keycloak's "Trusted Hosts" anonymous-DCR
policy rejects it. We pin `cib7-mcp` instead via
`--static-oauth-client-info '{"client_id":"cib7-mcp"}'`. If a future MCP
client speaks the URL form natively and prefers DCR, allow it via a
`clientRegistrationPolicies` block in the realm export.

## Connecting Claude Desktop (Windows quirks)

Claude Desktop's MCP config supports both `{ "url": "..." }` (native HTTP
transport) and the older `{ "command": "...", "args": [...] }` (stdio).
Current Claude Desktop **rejects the URL form on startup** with "not
valid MCP server configuration." So we go through `mcp-remote` — an npm
package that runs locally as a stdio child, opens an HTTP transport to
our `/mcp` endpoint, and handles the OAuth dance.

Three quirks bit us on Windows and the bridge script (`mcp/cib7-bridge.mjs`)
exists to work around all three:

1. **Windows shell mangling.** `npx -y mcp-remote http://localhost:3000/mcp
   --static-oauth-client-info '{"client_id":"cib7-mcp"}'` strips the inner
   double-quotes inside the JSON value when the args pass through
   `cmd.exe` and then `npx.cmd`. PowerShell's `&` operator does the same.
   `mcp-remote` then fails parsing the JSON. Solution: build the JSON in
   JavaScript and import `mcp-remote`'s entry directly with the assembled
   `process.argv`. No shell layer in the path.
2. **`{"url":...}` rejected.** Current Claude Desktop only accepts stdio.
   The bridge wraps the HTTP server in a stdio child it can spawn.
3. **Keycloak rejects DCR.** Pinning `client_id=cib7-mcp` via
   `--static-oauth-client-info` skips the registration call.

**One-time setup:**

```bash
npm install -g mcp-remote
```

The bridge expects it at `C:\nvm4w\nodejs\node_modules\mcp-remote\dist/proxy.js`
(npm global path on the dev box). Edit the `PROXY_ENTRY` constant in
`mcp/cib7-bridge.mjs` if your npm root differs (`npm root -g`).

**`claude_desktop_config.json`** (at `%APPDATA%\Claude\claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "cib7": {
      "command": "node",
      "args": [
        "C:\\Users\\<you>\\git\\cib7-react-poc\\mcp\\cib7-bridge.mjs"
      ]
    }
  }
}
```

Fully quit Claude Desktop (tray → Quit), reopen, open a new chat. The
first MCP tool call pops a browser to Keycloak; log in (or register).
Tokens are cached at `~/.mcp-auth/` and survive Claude Desktop restarts.

**Realm rebuild reset.** Re-importing the realm rotates Keycloak's
signing keys, so any cached `~/.mcp-auth/` tokens become invalid
signatures. The next MCP call returns 401 + `WWW-Authenticate` and
mcp-remote SHOULD trigger a fresh OAuth flow automatically — but the
re-auth UX is silent (browser pops with no Claude Desktop chat
notification). If the connector seems wedged:

```
1. Quit Claude Desktop (tray → Quit; closing the window is not enough).
2. rm -rf ~/.mcp-auth/
3. Reopen Claude Desktop. Next tool call triggers a clean OAuth dance.
```

For other MCP clients (Cursor, Codex, Windsurf, claude.ai web), once
they support the URL form natively, drop the bridge and use
`{ "url": "http://localhost:3000/mcp" }`. The MCP server itself is
client-agnostic.

## User registration and onboarding

Three paths an applicant can sign up, each with a different UX trade-off:

| Path | Tool / Surface | What the user does | Who owns the password |
|---|---|---|---|
| **Self-registration via the SPA** | "Register" button on `http://localhost:3000` → `keycloak.register()` | Fill the Keycloak form themselves (username, email, name, password ×2) → verify email in Mailpit → signed in. | The user, in Keycloak's hosted form. |
| **Self-registration via chat (URL lookup)** | `get_signup_url` MCP tool | LLM returns the same Keycloak registration URL + step-by-step. Useful when the user is chatting with Claude before they've discovered the SPA. | The user, in Keycloak's hosted form. |
| **Invite by email via chat** | `send_account_invitation` MCP tool | LLM collects `{ username, email, firstName, lastName }` — never a password. The tool creates the Keycloak user with `requiredActions: ["UPDATE_PASSWORD","VERIFY_EMAIL"]` via admin REST and triggers `execute-actions-email`. Invitee clicks the magic link in Mailpit, sets their own password in Keycloak's form, signed in. | The invitee, in Keycloak's hosted form. The MCP service never accepts or stores a password. |

All three paths land the user in the `/applicant` group via the realm's
`defaultGroups: ["/applicant"]`, which grants them `applicant` realm
role and applicant-scoped engine authorizations.

**Password reset** is symmetric to "self-registration via chat":
`get_password_reset_url` returns the Keycloak `kc_action=reset_credentials`
deep link. The user enters their email, gets a reset email at Mailpit,
clicks, sets a new password, signed in.

The LLM playbook for routing requests to the right tool lives in
`SERVER_INSTRUCTIONS` (top of `server.ts`). It's returned in the MCP
`initialize` handshake's `instructions` field. The key load-bearing
phrasing is *"This is a URL-lookup question, not an action you are
being asked to perform"* — Claude's safety training around credential
handling is conservative enough that the LLM will refuse to call
anything that reads like "create an account for the user" unless the
instructions explicitly reframe the tools as inert URL retrieval (for
`get_signup_url` / `get_password_reset_url`) or invite-by-email (for
`send_account_invitation`).

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
| Server-level metadata | `/.well-known/oauth-protected-resource` (proxied to `mcp:8090`) | OAuth2 protected-resource metadata (RFC 9728) — points to Keycloak as the authorization server. Returns `{ resource, authorization_servers, bearer_methods_supported, scopes_supported: ["openid"] }`. |
| MCP `instructions` | Returned in the initialize handshake on `/mcp` | LLM-facing playbook (the `SERVER_INSTRUCTIONS` constant in `server.ts`) — tells the model how to route registration / password-reset / "what can I do" questions to the right tools. |
| Per-service catalog | `/.well-known/mcp/services.json` (planned — see "follow-up" below) | The aggregated services index (auto-generated by `/service-builder`). |

**Follow-up wiring still loose** — two pieces called out but not yet shipped:

- `/.well-known/mcp/services.json` proxy is not in `frontend/nginx.conf`.
  The file exists at `docs/business/services/build/services.json` and is
  baked into the mcp container at `/app/services-spec/build/services.json`,
  but no nginx route exposes it. ~5 lines to add; the missing piece for
  headless-agent discovery without an OAuth handshake.
- `<meta name="mcp-server" content="/mcp">` tags in `frontend/index.html`.
  Standard MCP discovery convention for web crawlers; trivial to add but
  not in the build today.

Both are tracked as MCP-topic polish.

## Configuration surface (env vars)

| Env var | Where | Default | Purpose |
|---|---|---|---|
| `PORT` | `mcp/src/server.ts` | `8090` | HTTP port the sidecar listens on. |
| `MCP_RESOURCE_URL` | `mcp/src/server.ts` | `http://localhost:3000/mcp` | Browser-visible MCP URL (via nginx). Stamped into the OAuth resource metadata. |
| `MCP_APPLICANT_PORTAL_URL` | `mcp/src/server.ts` | `http://localhost:3000` | SPA base URL. Used as the redirect target on signup / invitation / reset flows so the user lands back at the applicant portal already signed in. |
| `MCP_MAILPIT_URL` | `mcp/src/server.ts` | `http://localhost:8025` | Browser-visible Mailpit URL. Surfaced in tool responses and `SERVER_INSTRUCTIONS` so Claude can tell the user where to read the verification / invitation email. |
| `KEYCLOAK_ISSUER_URL` | `mcp/src/server.ts`, `mcp/src/auth/verify.ts` | `http://localhost:8180/realms/cib7-poc` | Browser-visible Keycloak realm URL. Used in token signature verification (issuer claim) and stamped into hosted-page deep links. Must match `KC_HOSTNAME_URL` on the keycloak container. |
| `KEYCLOAK_INTERNAL_URL` | `mcp/src/auth/verify.ts`, `mcp/src/keycloak/admin.ts` | `http://keycloak:8080` | Docker-internal Keycloak URL. Used for JWKS fetch (signature verification) and admin REST calls — neither path needs to traverse the host network. |
| `KEYCLOAK_REALM` | `mcp/src/auth/verify.ts`, `mcp/src/keycloak/admin.ts` | `cib7-poc` | Realm name. |
| `KEYCLOAK_ADMIN_CLIENT_ID` | `mcp/src/keycloak/admin.ts` | `cib7-backend` | Service-account client used by `send_account_invitation` (client_credentials grant). |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | `mcp/src/keycloak/admin.ts` | `cib7-backend-secret` | Secret for the above. Realm export ships this; rotate in any real deployment. |
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
- **No persistence.** The sidecar holds no per-user state. Every tool
  call carries its own Bearer. The MCP transport runs in **stateless
  mode with a fresh Server + Transport per HTTP request** — sharing a
  single `Server` across requests breaks the MCP lifecycle because the
  SDK tracks the current request inside the Server instance, so two
  back-to-back requests racing on one Server cause the second one's
  `transport.handleRequest` to 500. See `createMcpServer()` in
  `server.ts`. The one cached server-side artifact is the cib7-backend
  service-account token (5-minute TTL, in-process), purely an optimization.
- **JWT verified for signature + issuer at the door; audience left to the
  engine.** `auth/verify.ts` runs JOSE `jwtVerify` against Keycloak's
  JWKS so stale tokens fail-fast with HTTP 401 + `WWW-Authenticate` —
  that's the signal mcp-remote needs to re-run OAuth. The audience
  (`cib7-rest-api`) is deliberately NOT checked here; the engine's
  `RestApiSecurityConfig` validates audience + signature + issuer again
  on every forwarded call, and surfacing the same failure at two layers
  doubles the debug surface without buying safety. `auth/identity.ts`
  reads `preferred_username` for query construction (`assignee=<me>`,
  `startedBy=<me>`) — parse only, signature already verified.
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
