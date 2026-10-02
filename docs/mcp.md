# MCP sidecar module (`mcp/`)

**When to read this:** before editing anything under `mcp/`; when changing
the MCP tool catalog, the engine forwarding logic, the OAuth wiring, or
the per-service manifest format; when wiring a different MCP host (Cursor,
Codex, Windsurf) against the deployment.

This module is the **standalone Model Context Protocol (MCP) microservice**
that exposes the CIB seven deployment as an AI-callable surface. Claude
Desktop (or any MCP-capable client) connects to `/mcp`, completes an
OAuth2 PKCE-loopback flow against Keycloak, and drives the deployment
through **sixteen MCP tools**: twelve process tools wrapping `/engine-rest`
and the backend, plus four onboarding tools (a "get started" overview,
sign-up URL lookup, password-reset URL lookup, and an email-invitation flow
that creates an invite-pending Keycloak user without ever handling a
password). Five of the tools also answer a signed-out caller
([lazy authentication](#lazy-authentication)); everything else needs the
user's own token. The sidecar is a **Bearer-proxy** in
front of `/engine-rest` — it forwards the caller's token unchanged on
every process call. It verifies the JWT (signature against Keycloak's
JWKS, expiry, issuer and the `cib7-rest-api` audience) at the door so a
stale or foreign token surfaces as a clean HTTP 401 and triggers the MCP
client's re-auth, but it never holds refresh tokens server-side and never
persists user state. The engine remains the authoritative security
boundary (issuer + audience + signature + per-user authorization on every
forwarded call). Security rules for this module are in
[security.md § 10](security.md#10-mcp-and-llm-agents); how the sidecar
meets them is in [Security controls](#security-controls).

**Contents**
1. [Stack](#stack)
2. [Architecture choice — why a sidecar, not an engine plugin](#architecture-choice--why-a-sidecar-not-an-engine-plugin)
3. [File layout](#file-layout)
4. [The sixteen tools](#the-sixteen-tools)
5. [Lazy authentication](#lazy-authentication)
6. [Auth — OAuth2 PKCE-loopback, step by step](#auth--oauth2-pkce-loopback-step-by-step)
7. [Connecting Claude](#connecting-claude)
8. [User registration and onboarding](#user-registration-and-onboarding)
9. [Manifest loading + per-service contracts](#manifest-loading--per-service-contracts)
10. [Discovery surface (`.well-known`, `<meta>` tags)](#discovery-surface-well-known-meta-tags)
11. [Configuration surface (env vars)](#configuration-surface-env-vars)
12. [Run, build, package](#run-build-package)
13. [Security controls](#security-controls)
14. [Conventions and extensions](#conventions-and-extensions)

---

## Stack

| | |
|---|---|
| Language | TypeScript 5.5 (strict) |
| Runtime | Node 24, run directly via `tsx` (no `tsc` build step) |
| Framework | Express 4 |
| MCP SDK | `@modelcontextprotocol/sdk` 1.18+ (Streamable HTTP transport, per-request server) |
| Schema validator | Ajv 8 + ajv-formats (JSON Schema draft 2020-12) |
| JWT verification | `jose` 5 against Keycloak's JWKS (signature + expiry + issuer + audience) |
| Rate limiting | `express-rate-limit` 8 (per IP before auth, per user after) |
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
├── package.json                # @modelcontextprotocol/sdk, express, express-rate-limit, tsx, ajv, ajv-formats, jose
├── tsconfig.json               # strict, noEmit (tsx runs source)
├── Dockerfile                  # node:20-alpine, COPYs from repo root
├── cib7-bridge.mjs             # stdio↔HTTP bridge for a local stack (see "Connecting Claude" below)
└── src/
    ├── server.ts               # Express + per-request MCP server/transport + tool registry + LLM instructions
    ├── untrusted.ts            # withUntrustedData: frames applicant-written values for the LLM
    ├── auth/
    │   ├── audience.ts         # REQUIRED_AUDIENCE (KEYCLOAK_REST_AUDIENCE, default cib7-rest-api)
    │   ├── identity.ts         # decodeBearerUsername (parse only — engine validates)
    │   ├── inviterRole.ts      # send_account_invitation: role + audience predicate, invitation quota
    │   ├── lazyAuth.ts         # /mcp door: public methods/tools allowlist, 401 challenge for the rest
    │   └── verify.ts           # jwtVerify against Keycloak JWKS — 401 + WWW-Authenticate on stale tokens
    ├── http/
    │   ├── guards.ts           # Host/Origin (DNS rebinding) guard, per-IP and per-user rate limits
    │   └── fixedWindow.ts      # in-process counter behind the invitation quota
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

## The sixteen tools

All return `{ ok: true, data }` on success or `{ ok: false, status, code,
message, retryable }` on failure. Schema-validation failures surface as
`{ code: 'INVALID_VARIABLES', issues: [...] }` (Ajv issues array) without
hitting `/engine-rest`. A missing token on a protected tool, or any stale
token, is caught at the `/mcp` door and returns HTTP 401 +
`WWW-Authenticate: Bearer error="invalid_token"`, which the MCP client treats
as "sign in" (see [Lazy authentication](#lazy-authentication)). Engine 5xx
surfaces as `{ retryable: true }`. Tools that return applicant-written
values (`search_cases`, `get_my_profile`, `query_user_history`) put them
under `untrustedData` behind a fixed `untrustedDataNote`; see
[Security controls](#security-controls).

**Process tools** (forward the caller's Bearer to `/engine-rest` or the backend; `list_services` and `describe_service` also work signed out):

| Tool | Engine endpoint | Purpose |
|---|---|---|
| `list_services` | `GET /engine-rest/process-definition?latestVersion=true` (signed out: no call, manifests only) | Deployed definitions decorated with `mcpCallable` flag from the manifest registry. Signed out, the catalog comes from the loaded manifests with `signedIn: false`. |
| `describe_service(key)` | (no engine call — reads in-memory manifest) | Returns the JSON Schema for `start_process` variables + the LLM training markdown. |
| `start_process(key, variables)` | `POST /engine-rest/process-definition/key/<k>/start` | Ajv-validates → maps to Camunda `{value, type}` → engine. |
| `list_my_tasks` | `GET /task?assignee=<me>` + `GET /task?candidateUser=<me>&unassigned=true` (merged, deduped) | Returns tasks the user can act on; `action: 'complete' \| 'claim_then_complete'`. |
| `get_form_schema(taskId)` | `GET /task/<id>` then look up by formKey | Returns the per-task JSON Schema + audience + description. |
| `complete_task(taskId, variables)` | (claim if unassigned and the caller is a candidate) + `POST /task/<id>/complete` | Ajv-validates against per-task schema → engine. Claims only after `GET /task/count?taskId=&candidateUser=<me>` confirms candidacy; a task assigned to someone else returns `TASK_ASSIGNED_TO_OTHER`, a non-candidate gets `NOT_A_CANDIDATE`, and a claim the engine refuses returns `CLAIM_FAILED` with the engine status, all without submitting. The tool description tells the agent to show the values (and for review tasks the approve / reject / send-back decision) to the human and get explicit confirmation first. |
| `save_draft(taskId, variables)` | `POST /task/<id>/localVariables` | Writes a partial, type-checked draft as task-local variables and returns a `portalUrl` where the SPA form opens prefilled. Nothing is submitted; the draft never enters process scope. |
| `upload_document(category, filename, contentType, base64)` | `POST /api/documents/stage` (backend) | Stages a PDF / JPEG / PNG (≤ 10 MB) and returns `{ pendingKey, filename, contentType }`, which the agent passes verbatim as the task's `requiredDocuments[i].writeTo` variable. |
| `list_my_processes(processInstanceId?)` | `GET /history/process-instance?startedBy=<me>&sortBy=startTime&sortOrder=desc` | Decorated with state (ACTIVE / COMPLETED / ...). |
| `query_user_history(variableName)` | Two-step: instances → variable-instance with `processInstanceIdIn` | Most recent value the user ever entered for that variable. Used for autofill (decision A3 / T15). |
| `get_my_profile()` | Token claims + the same two-step history query, unfiltered | One-shot autofill: identity from the Bearer claims plus the most recent value of every scalar variable the user ever entered (documents / JSON blobs / engine plumbing excluded). Supersedes per-field `query_user_history` calls. |
| `search_cases(query?, service?, status?)` | `GET /api/cases/search` (backend, not engine) | Case status cards — one prose card per process instance, re-posted by BPMN "Index case" milestone tasks (submitted / sent-back / awaiting-medical / rejected / completed) through the ESB to `POST /api/internal/cases/index` into a plain JPA table. Retrieval is keyword-ranked + exact service/status filters; the LLM does the semantic matching over the returned summaries (no embeddings). Hits are post-filtered through the backend's per-case access rule, so results only cover cases the caller may see. |

**Onboarding tools** (Keycloak instead of the engine — see [User registration and onboarding](#user-registration-and-onboarding)):

| Tool | Keycloak endpoint | Purpose |
|---|---|---|
| `get_started` | (none — reads the verified claims, if any) | Works signed out. Says whether the user is signed in, lists the services from the manifests, and gives next steps: how sign-in and registration work (with the sign-up and reset URLs) when signed out, which tools to use next when signed in. |
| `get_signup_url` | (none — builds the URL locally) | Works signed out. Returns the public hosted Keycloak registration URL plus the steps to relay to the user. Pure URL lookup; performs no action. |
| `get_password_reset_url` | (none — builds the URL locally) | Works signed out. Returns the public hosted Keycloak `kc_action=reset_credentials` URL plus the steps. Pure URL lookup. |
| `send_account_invitation(username, email, firstName, lastName)` | `POST /admin/realms/<r>/users` + `PUT /admin/realms/<r>/users/<id>/execute-actions-email` | Creates an invite-pending Keycloak user with `requiredActions: ["UPDATE_PASSWORD","VERIFY_EMAIL"]`, then triggers the magic-link email. The invitee sets their own password in Keycloak — the tool never accepts or returns one. Uses the `cib7-backend` service-account client (client_credentials grant), not the caller's Bearer. Requires the `cib7-rest-api` audience plus the `applicant`, `civil-servant` or `cib7-admin` realm role, and is capped at 5 invitations per user per hour and 50 overall (`RATE_LIMITED`). |

The username `<me>` (for process tools) is decoded from the Bearer's
`preferred_username` claim locally (parse only — `verifyBearer` already
ran at the door). See `mcp/src/auth/identity.ts`.

The tool surface is also wrapped with an MCP **`instructions`** field
returned in the initialize handshake. It tells the LLM how signing in works (which tools
work signed out, that the client shows a sign-in prompt for the rest, that a
session expires after inactivity), how to handle "I'm new" (`get_signup_url`
or the Register link), "I forgot my password", and "register someone else"
(`send_account_invitation`, signed in only), and to NEVER ask the user for a
password in chat. See the `SERVER_INSTRUCTIONS` constant at the top of
`server.ts`.

## Lazy authentication

A signed-out user can connect and learn what the deployment offers before
they have an account; signing in happens only when it is needed. The gate is
`lazyBearer` in `mcp/src/auth/lazyAuth.ts` (tests in `lazyAuth.test.ts`), and
it runs before the MCP SDK sees the request:

- **Without a token**, a POST passes only when every JSON-RPC message in it
  is public: `initialize`, `notifications/initialized`,
  `notifications/cancelled`, `ping`, `tools/list`, or a `tools/call` of
  `get_started`, `list_services`, `describe_service`, `get_signup_url` or
  `get_password_reset_url`. These answer from the manifests or build a URL
  and never touch user data. Anything else, including an unknown tool or
  method, a batch that mixes in one protected call, or a malformed body, gets
  HTTP 401 with
  `WWW-Authenticate: Bearer resource_metadata="<url>", error="invalid_token", error_description="Sign in required for this tool", scope="openid"`.
- **A 401 must come from the HTTP layer.** Claude starts sign-in, shows its
  Connect card and retries the same call only on a transport-level 401; a
  tool result saying "please sign in" arrives as a 200 and is just text for
  the model. That is why the check sits in front of the SDK.
- **With a token**, the token is verified whatever the call, so a stale token
  gets 401 even on a public tool and the client refreshes or re-authenticates.
- **An anonymous `GET /mcp`** (the optional server-to-client SSE stream) gets
  405: this stateless server pushes nothing, and an anonymous open stream
  would only hold a socket.
- **Backstop.** `dispatchTool` refuses any non-public tool without a token
  with `SIGN_IN_REQUIRED`, so a routing mistake cannot run a user-data tool
  anonymously.

The list is an allowlist on purpose: a new tool is protected until someone
decides it is harmless without an identity. Signed-out calls still count
against the per-IP limit, and the per-user limit keys them on the IP.


## Auth — OAuth2 PKCE-loopback, step by step

The MCP server is unauthenticated for its discovery endpoints
(`/.well-known/oauth-protected-resource`, `/health`) and Bearer-gated on
`/mcp`. The MCP client (Claude Desktop, possibly via the `mcp-remote`
stdio bridge — see next section) handles the full OAuth flow; the sidecar
challenges, verifies JWTs at the door, then forwards them.

```
1. MCP client POSTs /mcp. Without a Bearer, the handshake, tools/list and
   the public tools succeed. The first protected tool call (or any call with
   a stale Bearer) → 401 + WWW-Authenticate: Bearer resource_metadata="<url>",
   error="invalid_token".
2. MCP client fetches /.well-known/oauth-protected-resource from the sidecar.
   Sidecar returns { resource, authorization_servers: [keycloak issuer],
   bearer_methods_supported, scopes_supported: ["openid"] }.
3. MCP client fetches Keycloak's /.well-known/openid-configuration.
4. MCP client initiates OAuth2 Authorization Code + PKCE with client_id=cib7-mcp
   and scope=openid only. The redirect is https://claude.ai/api/mcp/auth_callback
   for claude.ai / Claude Desktop / mobile, or a loopback
   http://127.0.0.1:<random-port>/... for Claude Code and mcp-remote.
5. Browser pops to Keycloak's login page. The page includes "Register" and
   "Forgot Password?" links (realm flags registrationAllowed=true,
   resetPasswordAllowed=true). The user can register inline if they don't
   have an account; verifyEmail=false, so the new account signs in at once.
6. After successful login Keycloak redirects with the auth code. The client
   exchanges code → access_token + refresh_token and retries the tool call.
   The token carries: preferred_username + realm_access.roles (cib7-claims
   scope) and aud=cib7-rest-api (cib7-rest-api-audience scope).
7. MCP client attaches Authorization: Bearer <token> on every subsequent
   /mcp call.
8. mcp sidecar's lazyBearer middleware verifies the JWT against
   Keycloak's JWKS (signature, expiry, issuer, audience). On failure it
   returns HTTP 401 + WWW-Authenticate, which the client treats as a
   refresh or re-auth signal.
9. On verification success the sidecar forwards the same token to
   /engine-rest. RestApiSecurityConfig validates issuer + audience +
   signature again; KeycloakAuthenticationFilter binds the user into
   IdentityService; engine enforces per-user authorization.
```

**Session length.** Access tokens live 5 minutes and the client refreshes
them. The realm's SSO idle timeout is 30 minutes and there is no
`offline_access`, so after about half an hour without use the refresh fails
and the chat shows the sign-in prompt again. That is accepted for now;
`SERVER_INSTRUCTIONS` tells the agent to explain it.

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

- **Client `cib7-mcp`** — public client, PKCE required. Redirects: the
  loopback wildcards (`http://127.0.0.1/*`, `http://localhost/*`, any port)
  for Claude Code and mcp-remote, and `https://claude.ai/api/mcp/auth_callback`
  for claude.ai, Claude Desktop and mobile connectors. Default scopes:
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
- **Realm `frontendUrl`** attribute, set from the `${PUBLIC_KEYCLOAK_URL}`
  placeholder — forces every generated link (OIDC issuer, action-token
  emails) to the public URL regardless of which container called the admin
  API. Without
  this, invitation emails triggered from the MCP container would contain
  `http://keycloak:8080/...` links the browser can't reach.
- **Realm flags** — `registrationAllowed` and `resetPasswordAllowed`
  `true`, `verifyEmail` `false` (a self-registered account works at once;
  on the public deployment the inbox is Mailpit behind a login, so a
  verification mail could not be read); `defaultGroups: ["/applicant"]` so any
  self-registered or invited user lands in the applicant group; SMTP
  pointed at `mailpit:1025`.

Both realm files change together: `keycloak/realm-export.json` (local
compose) and `deploy/keycloak/realm-export.json` (the public deployment).
The realm is imported once, at Keycloak's first start, so a change reaches a
running deployment only through `deploy.sh --realm`, which recreates
Keycloak and drops users registered at runtime. Restart `cib7` once Keycloak
is healthy again; the engine exits if Keycloak is down at its startup.

**Why clients pass a fixed `client_id`.** MCP clients identify themselves by
Dynamic Client Registration (DCR) or a Client ID Metadata Document (CIMD)
when they can. Keycloak 26.1.5 offers neither: its "Trusted Hosts"
anonymous-DCR policy rejects the registration, and CIMD only arrived as an
experimental feature in Keycloak 26.6. So every client is given `cib7-mcp`:
in the connector's Advanced settings, with `--client-id` for Claude Code,
and via `--static-oauth-client-info` in the bridge. Upgrading Keycloak and
enabling CIMD is the later zero-config path.

## Connecting Claude

**claude.ai, Claude Desktop, Claude mobile (custom connector).** Customize >
Connectors > Add custom connector, URL `https://<host>/mcp`, and under
Advanced settings OAuth Client ID `cib7-mcp`, no secret. The connector is
reached from Anthropic's cloud (`160.79.104.0/21`), even when the user runs
Claude Desktop locally, so only the public deployment works this way, and
Keycloak's discovery endpoints must be reachable from that range too. The
`resource` in the protected resource metadata must equal the URL the user
enters, so `MCP_RESOURCE_URL` must be the public `/mcp` URL.

**Claude Code.**

```bash
claude mcp add --transport http --client-id cib7-mcp cib7 https://<host>/mcp
```

Claude Code redirects to a loopback port, which the `cib7-mcp` wildcards
already allow.

Either way, connecting asks for nothing. The first tool call that needs the
user's account shows a sign-in prompt in the chat, and the call is retried
after sign-in.

**Local stack: the stdio bridge.** The cloud cannot reach
`http://localhost:3000`, so for a local stack Claude Desktop still goes
through `mcp-remote`, launched by `mcp/cib7-bridge.mjs`. The launcher exists
because Windows shells strip the inner quotes of the inline JSON passed to
`--static-oauth-client-info`; it builds the JSON in JavaScript and imports
`mcp-remote`'s entry with the assembled `process.argv` instead. It finds
`mcp-remote` through `npm root -g`, exits with an install hint if it is
missing, and targets `http://localhost:3000/mcp` unless `CIB7_MCP_URL` is set.

```bash
npm install -g mcp-remote
```

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

Fully quit Claude Desktop (tray > Quit), reopen, open a new chat. Tokens are
cached at `~/.mcp-auth/` and survive Claude Desktop restarts.

**Realm rebuild reset.** Re-importing the realm rotates Keycloak's
signing keys, so any cached `~/.mcp-auth/` tokens become invalid
signatures. The next MCP call returns 401 + `WWW-Authenticate` and
mcp-remote SHOULD trigger a fresh OAuth flow automatically, but the
re-auth UX is silent (browser pops with no Claude Desktop chat
notification). If the connector seems wedged:

```
1. Quit Claude Desktop (tray > Quit; closing the window is not enough).
2. rm -rf ~/.mcp-auth/
3. Reopen Claude Desktop. Next tool call triggers a clean OAuth dance.
```

## User registration and onboarding

Three paths an applicant can sign up, each with a different UX trade-off:

| Path | Tool / Surface | What the user does | Who owns the password |
|---|---|---|---|
| **Self-registration via the SPA** | "Register" button on `http://localhost:3000` → `keycloak.register()` | Fill the Keycloak form themselves (username, email, name, password ×2) → signed in at once (no email verification). | The user, in Keycloak's hosted form. |
| **Self-registration via chat** | `get_signup_url` or `get_started` (both work signed out), or the "Register" link on the sign-in page the connector opens | LLM returns the same Keycloak registration URL + step-by-step. Useful when the user is chatting with Claude before they've discovered the SPA. | The user, in Keycloak's hosted form. |
| **Invite by email via chat** | `send_account_invitation` MCP tool (inviter must be signed in) | LLM collects `{ username, email, firstName, lastName }` — never a password. The tool creates the Keycloak user with `requiredActions: ["UPDATE_PASSWORD","VERIFY_EMAIL"]` via admin REST and triggers `execute-actions-email`. Invitee clicks the magic link in Mailpit, sets their own password in Keycloak's form, signed in. | The invitee, in Keycloak's hosted form. The MCP service never accepts or stores a password. |

All three paths land the user in the `/applicant` group via the realm's
`defaultGroups: ["/applicant"]`, which grants them `applicant` realm
role and applicant-scoped engine authorizations.

**Who may invite.** Applicants keep the right to invite on purpose:
invite-by-email is the onboarding path `SERVER_INSTRUCTIONS` steers agents
to ("add Lisa to the system"), and an invitee only ever lands in
`/applicant`, so an applicant can create nothing they could not get by
self-registering. What an applicant could abuse is Keycloak sending email to
arbitrary addresses, so every inviter is held to a per-user quota
(`MCP_INVITES_PER_USER_PER_HOUR`, default 5) and a global one
(`MCP_INVITES_PER_HOUR`, default 50, so a batch of fresh accounts cannot
turn the realm into a mail relay either). Attempts count, not just
successes. The counters are in-process and reset when the container
restarts.

**Password reset** is symmetric to "self-registration via chat":
`get_password_reset_url` returns the Keycloak `kc_action=reset_credentials`
deep link. The user enters their email, gets a reset email at Mailpit,
clicks, sets a new password, signed in.

**Mail on the public deployment.** Reset and invitation emails still go to
Mailpit, which on the public deployment sits behind a Keycloak login. A user
who cannot sign in cannot read their reset mail there, and an invitee cannot
read their invitation, so on that deployment both flows are effectively
admin-assisted until Mailpit is replaced by a real SMTP relay.

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
├── vehicle-registration/
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

The deployment advertises its MCP support in several layers so a visiting AI
agent (or developer reading the SPA) can discover it without prior
configuration — including from a bare `GET /` before any page renders:

| Layer | Where | What it carries |
|---|---|---|
| HTTP `Link` header | On every `GET /` (added by `frontend/nginx.conf`) | `</mcp>; rel="mcp-server"`, the manifest, and the OAuth metadata — the highest-signal hint for a headless agent that only fetches the homepage. Plus an `X-MCP-Server: /mcp` header. |
| Top-level manifest | `/.well-known/mcp.json` (served by `mcp:8090`) | Self-describing JSON: endpoint, `streamable-http` transport, OAuth pointer, a paste-ready `mcp-remote` client config, the `SERVER_INSTRUCTIONS` playbook, and the live service catalog. |
| `llms.txt` | `/llms.txt` (served by `mcp:8090`) | The [llmstxt.org](https://llmstxt.org) plain-text convention — readable with no MCP handshake. Endpoint, auth, how to connect (custom connector with client id `cib7-mcp`, Claude Code command), the service list, and getting-started text that says which tools work signed out. |
| HTML `<meta>` / `<link>` | `frontend/index.html` | `<meta name="mcp-server" content="/mcp">` + `<link rel="mcp-manifest" href="/.well-known/mcp.json">` for agents that parse page HTML. |
| Server-level metadata | `/.well-known/oauth-protected-resource`, also at the path-suffixed `/.well-known/oauth-protected-resource/mcp` that Claude tries first (RFC 9728 § 3.1; proxied to `mcp:8090`) | OAuth2 protected-resource metadata (RFC 9728) — points to Keycloak as the authorization server. Returns `{ resource, authorization_servers, bearer_methods_supported, scopes_supported: ["openid"] }`. |
| Per-service catalog | `/.well-known/mcp/services.json` (served by `mcp:8090`) | The aggregated services index, derived live from the loaded manifests. |
| MCP `instructions` | Returned in the initialize handshake on `/mcp` | LLM-facing playbook (the `SERVER_INSTRUCTIONS` constant in `server.ts`) — tells the model how to route registration / password-reset / "what can I do" questions to the right tools. |

The `.json` / `llms.txt` / `oauth-protected-resource` paths (both forms) are
routed to the MCP sidecar in **both** `frontend/nginx.conf` (the local /
Traefik-down fallback) and `deploy/traefik/dynamic/routes.yml` (the live
ingress, copied from `routes.yml.example`; an existing copy on a server needs
the `/.well-known/oauth-protected-resource/mcp` path added by hand) so they
return real content, not the SPA shell. Any other `/.well-known/*` probe
returns an honest `404` (`location /.well-known/` in nginx) rather than a
misleading `200` of `index.html`.

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
| `BUSINESS_URL` | `mcp/src/engine/client.ts` | `http://backend:8085` | Internal base URL of the business microservice. `upload_document` stages files via its `/api/documents/stage` endpoint (Bearer-proxied, same as engine calls). |
| `SERVICES_SPEC_DIR` | `mcp/src/services/manifest.ts` | `/app/services-spec` | Where the manifest loader looks for `*/build/mcp-service.json`. |
| `KEYCLOAK_REST_AUDIENCE` | `mcp/src/auth/audience.ts` | `cib7-rest-api` | Audience every accepted token must carry. Same variable and default as the engine and backend. |
| `MCP_ALLOWED_HOSTS` | `mcp/src/http/guards.ts` | hostname of `MCP_RESOURCE_URL` + `localhost`, `127.0.0.1`, `[::1]` | Comma-separated hostnames (no ports) `/mcp` answers to; also checked against a browser `Origin`. Replaces the default when set. |
| `MCP_TRUST_PROXY` | `mcp/src/server.ts` | `loopback, linklocal, uniquelocal` | Express `trust proxy` value: which hops' `X-Forwarded-For` to believe when keying the per-IP limit. |
| `MCP_RATE_LIMIT_PER_IP_PER_MINUTE` | `mcp/src/server.ts` | `300` | Requests per client IP per minute on `/mcp`, checked before token verification. |
| `MCP_RATE_LIMIT_PER_USER_PER_MINUTE` | `mcp/src/server.ts` | `120` | Requests per verified `sub` per minute on `/mcp`. |
| `MCP_INVITES_PER_USER_PER_HOUR` | `mcp/src/auth/inviterRole.ts` | `5` | `send_account_invitation` attempts per user per hour. |
| `MCP_INVITES_PER_HOUR` | `mcp/src/auth/inviterRole.ts` | `50` | `send_account_invitation` attempts per hour across all users. |

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
  BUSINESS_URL=http://localhost:8085 \
  KEYCLOAK_ISSUER_URL=http://localhost:8180/realms/cib7-poc \
  MCP_RESOURCE_URL=http://localhost:3000/mcp \
  SERVICES_SPEC_DIR=../docs/business/services \
  npm start
```

The container exposes only `8090` internally; nginx is the public face on
port 3000. There's no host port mapping on `mcp` so probing it from the
host requires going through nginx or `docker exec`.

## Security controls

How the sidecar meets [security.md § 10](security.md#10-mcp-and-llm-agents).
Every control has a negative test under `mcp/src/`.

- **Token checks.** Signature, expiry, issuer and audience. A token whose
  `aud` lacks `cib7-rest-api` (or has no `aud` at all) gets 401 with
  `error_description="wrong_audience"`. Test: `auth/verify.test.ts`.
- **Request pipeline on `/mcp`**, in this order: Host/Origin guard → per-IP
  rate limit → lazy token check (`lazyBearer`: a sent token is always
  verified; without one, only the public methods and tools pass, everything
  else gets 401, an anonymous GET 405) → per-user rate limit (keyed on the
  IP when signed out) → MCP transport. Tests: `auth/lazyAuth.test.ts`.
  - The Host guard is the SDK's `hostHeaderValidation` middleware
    (port-agnostic hostname match). The SDK's transport options
    `allowedHosts` / `enableDnsRebindingProtection` are deprecated in SDK
    1.29 and compare the Host header with its port, so they are not used.
    The default list (resource URL hostname plus loopback) covers nginx,
    which forwards `Host: $http_host` (`localhost:3000` locally, the public
    host in a deployment), Traefik, and the compose healthcheck on
    `127.0.0.1:8090`. A browser `Origin`, when present, must match the same
    list; native MCP clients send none.
  - Rate limits answer HTTP 429 with a JSON-RPC error body and
    `RateLimit` headers. Counters are in memory, fine for the single
    replica this stack runs.
  - Tests: `http/guards.test.ts`.
- **Applicant data is framed as untrusted.** `search_cases`,
  `get_my_profile` and `query_user_history` return user-written values only
  under `untrustedData`, after a fixed `untrustedDataNote` telling the agent
  to treat them as data, never as instructions. `SERVER_INSTRUCTIONS`
  repeats the rule. Test: `untrusted.test.ts`.
- **Human confirmation before `complete_task`.** Its description and
  `SERVER_INSTRUCTIONS` tell the agent to show the values, and for a review
  task the decision, to the human and wait for an explicit yes. Engine
  authorization, not the agent, still decides what the call may do; the
  sidecar only claims a task after the engine confirms the caller is a
  candidate, and surfaces a refused claim as `CLAIM_FAILED`.
- **Invitation quota.** See [Who may invite](#user-registration-and-onboarding).
  Test: `auth/inviterRole.test.ts`.

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
- **JWT fully verified at the door.** `auth/verify.ts` runs JOSE
  `jwtVerify` against Keycloak's JWKS with issuer and audience, so stale
  or foreign tokens fail fast with HTTP 401 + `WWW-Authenticate`, the
  signal the MCP client needs to refresh or re-run OAuth. A request
  without any token reaches only the public tools (see
  [Lazy authentication](#lazy-authentication)); a new tool is protected
  unless it is added to `PUBLIC_TOOLS` in `auth/lazyAuth.ts`. The engine checks the same
  things again on every forwarded call; the sidecar cannot rely on that
  alone because `send_account_invitation` never reaches the engine.
  `auth/identity.ts` reads `preferred_username` for query construction
  (`assignee=<me>`, `startedBy=<me>`) — parse only, signature already
  verified.
- **New tools that return user-written text use `withUntrustedData`.**
  Anything an applicant or portal user typed (names, reasons, summaries,
  variable values) goes under `untrustedData`; server guidance stays in
  the trusted fields.
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
