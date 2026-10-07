# MCP sidecar (`mcp/`)

Standalone Node sidecar that exposes the CIB seven engine as a Model
Context Protocol (MCP) server. Mirrors the `pdf-renderer/` shape — Node 20

- Express + a TypeScript entry — and runs as its own docker-compose
  service behind nginx at `http://localhost:3000/mcp`.

**See [`docs/mcp.md`](../docs/mcp.md) for the full module reference**
(architecture decisions, file layout, the sixteen tools, the OAuth flow,
realm artifacts, env vars, conventions). This README is the quick-start.

## Sixteen tools

Twelve tools wrap `/engine-rest` and the backend with per-service variable
schemas and training markdown:

`list_services`, `describe_service`, `start_process`, `list_my_tasks`,
`get_form_schema`, `complete_task`, `save_draft`, `upload_document`,
`search_cases`, `get_my_profile`, `list_my_processes`, `query_user_history`.

Four tools handle onboarding ("where do I start", "I'm new", "I forgot my
password", "register someone") without ever asking Claude to handle
credentials:

`get_started`, `get_signup_url`, `get_password_reset_url`,
`send_account_invitation`.

Every request needs the user's own token, the handshake included, so the MCP
client opens the Keycloak login when the connector is added or connected.

The full per-tool table with engine endpoints and behavior lives in
`docs/mcp.md` §[The sixteen tools](../docs/mcp.md#the-sixteen-tools).

## Endpoints

| Path                                        | Purpose                                                                                                                                                                                                                             |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /.well-known/oauth-protected-resource` | RFC 9728 / MCP auth-spec resource metadata. Tells the MCP client which authorization server to use (Keycloak). Also served at `/.well-known/oauth-protected-resource/mcp`, the path-suffixed form Claude tries first.               |
| `POST /mcp`                                 | MCP Streamable HTTP transport. Without a valid token (verified against Keycloak JWKS) every request, `initialize` included, gets 401 + `WWW-Authenticate: Bearer error="invalid_token"`, which makes the client run the OAuth flow. |
| `GET /health`                               | Liveness probe for docker-compose.                                                                                                                                                                                                  |

## Build & run

```bash
docker compose up --build mcp
```

The sidecar exposes port 8090 internally; nginx proxies it at
`http://localhost:3000/mcp`. No host port mapping on `mcp` itself — probe
it through nginx or `docker exec`.

For local dev without Docker, see `docs/mcp.md` §[Run, build, package](../docs/mcp.md#run-build-package).

## Connecting Claude

**claude.ai, Claude Desktop, Claude mobile.** Add the server as a custom
connector: Customize > Connectors > Add custom connector, URL
`https://<host>/mcp`, and under Advanced settings OAuth Client ID `cib7-mcp`
with no secret. The connector is reached from Anthropic's cloud
(`160.79.104.0/21`), not from your PC, so this works only against the public
deployment, and Keycloak's discovery endpoints must be reachable from there
too. The client id is needed because Keycloak 26.1.5 offers neither anonymous
Dynamic Client Registration (its Trusted Hosts policy refuses it) nor Client ID
Metadata Documents (experimental from Keycloak 26.6). CIMD is the later
zero-config path. See `docs/mcp.md` §[Connecting Claude](../docs/mcp.md#connecting-claude).

**Claude Code.**

```bash
claude mcp add --transport http --client-id cib7-mcp cib7 https://<host>/mcp
```

Claude Code uses a loopback redirect, which `cib7-mcp` already allows.

**A local stack the cloud cannot reach** (stdio bridge for Claude Desktop):

```bash
npm install -g mcp-remote
```

`mcp/cib7-bridge.mjs` finds `mcp-remote` through `npm root -g` and exits with
an install hint when it is missing. It connects to `http://localhost:3000/mcp`
unless `CIB7_MCP_URL` says otherwise. Add it to
`%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "cib7": {
      "command": "node",
      "args": ["C:\\Users\\<you>\\git\\cib7-react-poc\\mcp\\cib7-bridge.mjs"]
    }
  }
}
```

Fully quit Claude Desktop (tray > Quit; closing the window keeps the old
mcp-remote child alive) and reopen.

Whichever way you connect, connecting opens Keycloak's login page: log in as
a seeded user (`bart` / `bart`, `homer` / `homer`) or click **Register**. A
new account works immediately; there is no email verification step. After
about 30 minutes without use the session expires and Claude marks the
connector as needing sign-in; reconnect it in the connector settings
(Settings > Connectors > Connect) to log in again.

## Verify the connection end-to-end

```bash
# 1. All containers healthy
docker compose ps

# 2. Discovery endpoint
curl http://localhost:3000/.well-known/oauth-protected-resource

# 3. The handshake without a Bearer gets the sign-in challenge
curl -i -X POST http://localhost:3000/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
# → HTTP/1.1 401 Unauthorized
# → WWW-Authenticate: Bearer resource_metadata="...", error="invalid_token", error_description="missing", scope="openid"

# 4. 401 with WWW-Authenticate on a bogus token
curl -i -X POST http://localhost:3000/mcp -H "Authorization: Bearer not.a.real.token" -H "Content-Type: application/json" -d '{}'
# → HTTP/1.1 401 Unauthorized
# → error_description="other"
```

In Claude, on a fresh chat:

> What services are available on the cib7 server?

Expected: `get_started` or `list_services` lists the two services
(`businessRegistration`, `vehicleRegistration`).

> Register Acme OÜ for me. I'm 35, share capital €3000, board member Bart Simpson 38501010001.

Expected: Claude calls `describe_service('businessRegistration')`, then
`start_process` and, after you confirm the values, `complete_task`. Demo mode
routes every case to civil-servant review: approve it as `homer` in the SPA,
and the approval email lands at http://localhost:8025 (bring the inbox online
with `docker compose --profile dev up -d mailpit-ui`; the default profile keeps
it network-internal). The case then waits for payment on the web.

## Try the registration / invitation flow

> I want to register a new user — username lisa, email lisa@example.com, first name Lisa, last name Simpson.

Expected: Claude calls `send_account_invitation` with those four fields,
never asking for a password. The tool needs a signed-in inviter. The tool creates the Keycloak user with
`requiredActions: ["UPDATE_PASSWORD","VERIFY_EMAIL"]` and triggers the
magic-link email. Lisa opens Mailpit, clicks the link, sets her own
password in Keycloak's form, and lands in the SPA signed in.

> Where do I sign up?

Expected: Claude calls `get_signup_url` and returns the
portal sign-up link (`/?auth=register`), which starts Keycloak registration. The user fills the form themselves and
is signed in at once.

> I forgot my password.

Expected: Claude calls `get_password_reset_url` and returns
Keycloak's standalone reset page (`/login-actions/reset-credentials`). The user resets it themselves.
The reset email goes to Mailpit, which on the public deployment sits behind a
login, so there a reset is effectively admin-assisted.

## Realm-rebuild reset (dev only)

Re-importing the realm rotates Keycloak's signing keys, so the
`~/.mcp-auth/` cache holds invalid-signature tokens. The next MCP call
returns 401 + `WWW-Authenticate` and `mcp-remote` SHOULD re-run OAuth
automatically. If the connector looks wedged in Claude Desktop:

```
1. Quit Claude Desktop (tray → Quit).
2. rm -rf ~/.mcp-auth/
3. Reopen Claude Desktop. Next chat message triggers a clean OAuth dance.
```

## Logging

Every tool call logs one line — the tool name plus the caller's Keycloak
username — to the console _and_ to Graylog as structured GELF. That makes "who
asked the assistant to do what, and did it work" one search rather than a grep
through container output:

```
service:mcp AND _tool:start_process
service:mcp AND level_name:ERROR
user_id:bart
```

Graylog is reachable over an SSH tunnel only (`ssh -N -L 9900:127.0.0.1:9900
<user>@<host>`, then <http://localhost:9900>). Set `GRAYLOG_HOST` to turn
shipping on; unset, the sidecar logs to the console only and says so in its
startup banner. Details in [`../docs/logging.md`](../docs/logging.md).

## Troubleshooting

| Symptom                                                                                          | Likely cause                                                                                                                                                                                                                                                                                           |
| ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Claude Desktop says "not valid MCP server configuration" and skips cib7                          | `claude_desktop_config.json` does not take the `{"url":...}` form. Add the server as a custom connector in the app instead, or use the `cib7-bridge.mjs` stdio bridge for a local stack (see above).                                                                                                   |
| Custom connector: "Couldn't reach the MCP server" or sign-in fails                               | The URL must be the public HTTPS deployment (Anthropic's cloud cannot reach localhost), the Advanced settings OAuth Client ID must be `cib7-mcp`, and the realm must list `https://claude.ai/api/mcp/auth_callback` on `cib7-mcp` (re-import a realm imported before that change).                     |
| Claude connects without a login and then tells you to sign in or reconnect                       | An old mcp image that still lets a tokenless handshake through (lazy auth). Rebuild `mcp`; `initialize` without a token must answer HTTP 401.                                                                                                                                                          |
| `InsufficientScopeError: Policy 'Trusted Hosts' rejected request to client-registration service` | The client tried Dynamic Client Registration, which Keycloak refuses anonymously. Give it the client id: `cib7-mcp` under Advanced settings, `--client-id cib7-mcp` for Claude Code. The bridge already passes it; if the bridge shows this, update `mcp-remote` (`npm install -g mcp-remote@latest`). |
| Bridge exits with `mcp-remote not found at ...`                                                  | Run `npm install -g mcp-remote` with the same npm that is on Claude Desktop's PATH.                                                                                                                                                                                                                    |
| mcp-remote bridge: `SyntaxError: Expected property name or '}' in JSON`                          | Windows shell stripped quotes from the inline JSON. Make sure Claude Desktop is invoking `cib7-bridge.mjs` via `node`, not piping through `cmd /c npx ...`.                                                                                                                                            |
| OAuth callback says `invalid_scope: Invalid scopes: openid profile email`                        | mcp-remote requested scopes the realm doesn't define. The MCP service advertises only `openid` — rebuild it (`docker compose up -d --build mcp`) and clear `~/.mcp-auth/` so the bridge re-reads discovery.                                                                                            |
| `Authorization successful` in browser but the bridge errors with "Error POSTing to endpoint"     | Stale state from before the per-request transport refactor. Restart the MCP container (`docker compose restart mcp`) and retry.                                                                                                                                                                        |
| `list_services` returns `code: 'engine_unauthorized'`                                            | The Bearer doesn't carry `aud=cib7-rest-api`. The audience mapper lives on the `cib7-rest-api-audience` client scope; confirm it's a default scope on `cib7-mcp` (admin UI → Clients → cib7-mcp → Client scopes → Default).                                                                            |
| `start_process` returns `code: 'forbidden'`                                                      | The user's group doesn't grant CREATE_INSTANCE on the definition. `AuthorizationBootstrap.java` grants `ProcessDefinition:*` to the `applicant` group; self-registered users land in `/applicant` via `defaultGroups`.                                                                                 |
| `describe_service` returns `code: 'unknown_service'`                                             | The manifest didn't make it into the container. `docker exec cib7-poc-mcp ls /app/services-spec/<service>/build/` should show `mcp-service.json` + `mcp-training.md`. If empty, the build context or `.dockerignore` is misconfigured — see `Dockerfile`.                                              |
| `list_my_tasks` returns empty even though Cockpit shows tasks                                    | The user is neither assigned nor a candidate for the open tasks. Check the BPMN's `camunda:assignee` / `candidateGroups` and the user's Keycloak group membership.                                                                                                                                     |
| `complete_task` returns `INVALID_VARIABLES` with `"must NOT have additional properties"`         | Claude included a field the per-task schema doesn't accept. Pass only the fields `get_form_schema` listed.                                                                                                                                                                                             |
| `send_account_invitation` returns `KEYCLOAK_ERROR: HTTP 401 Unauthorized`                        | cib7-backend's service-account token is stale, typically right after a realm re-import. Restart the MCP container to clear the in-process token cache.                                                                                                                                                 |
| Invitation email arrives but the link points at `http://keycloak:8080/...`                       | Realm `frontendUrl` attribute isn't set or the keycloak container wasn't rebuilt after editing the realm export. The export now ships `attributes.frontendUrl: "http://localhost:8180"`; re-import via `docker compose rm -sf keycloak && docker compose up -d keycloak`.                              |
| `query_user_history` returns `found: false` despite history existing                             | Prior process instances were wiped by an engine restart (in-memory H2). Complete at least one registration flow as that user to repopulate history. Permanent fix is in TODOS.md T1 (H2 → Postgres).                                                                                                   |

## Files

- `package.json` — `@modelcontextprotocol/sdk`, `express`, `tsx`, `ajv`, `ajv-formats`, `jose`.
- `tsconfig.json` — strict TypeScript with `noEmit` (tsx interprets at runtime).
- `Dockerfile` — `node:24-alpine`, no multi-stage; installs runtime dependencies only (`npm ci --omit=dev`), then removes npm, so the container starts with `node --import tsx src/server.ts`, the same thing `npm start` runs locally. COPYs from the repo root so it can include the core's shared schema definitions (`cib7/src/main/resources/schemas/core-v1.json`); a pack's `docker/mcp.Dockerfile` layer adds its specs folder and form schemas.
- `cib7-bridge.mjs` — Node launcher for a local stack, used by Claude Desktop's `claude_desktop_config.json`. Finds `mcp-remote` through `npm root -g` and imports its entry directly with assembled `process.argv` to avoid shell-quoting issues; `CIB7_MCP_URL` overrides the target.
- `src/server.ts` — Express + per-request MCP `Server` + `StreamableHTTPServerTransport`. Tool registry, `SERVER_INSTRUCTIONS` LLM playbook, AsyncLocalStorage bearer-context.
- `src/auth/requireBearer.ts` — the `/mcp` door: 401 + `WWW-Authenticate` for every request without a valid token, so the client signs in at connect time.
- `src/auth/verify.ts` — JOSE `jwtVerify` against Keycloak JWKS (signature, expiry, issuer, audience).
- `src/auth/identity.ts` — parse-only `preferred_username` extraction for query construction (assignee / startedBy).
- `src/engine/client.ts` — Bearer-forward `/engine-rest` fetch wrapper. `{ ok, status, code, message, retryable, data }` envelope. Stateless A2.
- `src/engine/variables.ts` — Plain JSON → Camunda `{ value, type }` envelope, schema-driven.
- `src/keycloak/admin.ts` — `cib7-backend` service-account token (client_credentials, 5-min in-process cache) + admin REST wrapper. Used by `send_account_invitation`.
- `src/services/manifest.ts` — Walks `/app/services-spec`, compiles each offered form's engine schema (from the pack, core definitions inlined), indexes by `formKey`.
- `src/logging/gelf.ts` — GELF UDP logger. One datagram per log call to `graylog:12201`, mirrored to the console. Every message carries `service`, `level` + `level_name`, and `user_id` from the per-request bearer context. Hand-rolled to keep the dependency list short — this sidecar forwards user Bearers, so every package sits in the trust path. See [`../docs/logging.md`](../docs/logging.md).
