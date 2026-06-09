# MCP sidecar (`mcp/`)

Standalone Node sidecar that exposes the CIB seven engine as a Model
Context Protocol (MCP) server. Mirrors the `pdf-renderer/` shape — Node 20
+ Express + a TypeScript entry — and runs as its own docker-compose
service behind nginx at `http://localhost:3000/mcp`.

**See [`docs/mcp.md`](../docs/mcp.md) for the full module reference**
(architecture decisions, file layout, the eleven tools, the OAuth flow,
realm artifacts, env vars, conventions). This README is the quick-start.

## Eleven tools

Eight tools wrap `/engine-rest` with per-service variable schemas and
training markdown:

`list_services`, `describe_service`, `start_process`, `list_my_tasks`,
`get_form_schema`, `complete_task`, `list_my_processes`,
`query_user_history`.

Three identity tools handle "I'm new" / "I forgot my password" /
"register someone" without ever asking Claude to handle credentials:

`get_signup_url`, `get_password_reset_url`, `send_account_invitation`.

The full per-tool table with engine endpoints and behavior lives in
`docs/mcp.md` §[The eleven tools](../docs/mcp.md#the-eleven-tools).

## Endpoints

| Path | Purpose |
|---|---|
| `GET /.well-known/oauth-protected-resource` | RFC 9728 / MCP auth-spec resource metadata. Tells the MCP client which authorization server to use (Keycloak). |
| `POST /mcp` | MCP Streamable HTTP transport. JWT-verified at the door against Keycloak JWKS — stale tokens return 401 + `WWW-Authenticate: Bearer error="invalid_token"`, which mcp-remote treats as "re-run OAuth." |
| `GET /health` | Liveness probe for docker-compose. |

## Build & run

```bash
docker compose up --build mcp
```

The sidecar exposes port 8090 internally; nginx proxies it at
`http://localhost:3000/mcp`. No host port mapping on `mcp` itself — probe
it through nginx or `docker exec`.

For local dev without Docker, see `docs/mcp.md` §[Run, build, package](../docs/mcp.md#run-build-package).

## Connecting Claude Desktop (Windows)

Current Claude Desktop on Windows requires the stdio-bridge route through
`mcp-remote` plus a Node launcher that bypasses shell quoting (the JSON
flag value gets mangled by `cmd.exe` and PowerShell). See
`docs/mcp.md` §[Connecting Claude Desktop](../docs/mcp.md#connecting-claude-desktop-windows-quirks)
for the full why.

**One-time setup:**

```bash
npm install -g mcp-remote
```

If `npm root -g` is anything other than `C:\nvm4w\nodejs\node_modules`,
edit the `PROXY_ENTRY` constant in `mcp/cib7-bridge.mjs`.

**Add to `%APPDATA%\Claude\claude_desktop_config.json`:**

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

Fully quit Claude Desktop (tray → Quit; closing the window keeps the old
mcp-remote child alive) and reopen. The first MCP tool call pops a browser
to Keycloak's login page — log in as a seeded user (`bart` / `bart`,
`homer` / `homer`) or click **Register** to create your own account.

For other MCP clients (Cursor, Codex, claude.ai web Custom Connectors)
that already speak the URL form, skip the bridge entirely and use
`{ "url": "http://localhost:3000/mcp" }`.

## Verify the connection end-to-end

```bash
# 1. All containers healthy
docker compose ps

# 2. Discovery endpoint
curl http://localhost:3000/.well-known/oauth-protected-resource

# 3. 401 challenge on /mcp without a Bearer
curl -i -X POST http://localhost:3000/mcp -H "Content-Type: application/json" -d '{}'
# → HTTP/1.1 401 Unauthorized
# → WWW-Authenticate: Bearer resource_metadata="...", error="invalid_token", error_description="missing"

# 4. 401 with WWW-Authenticate on a bogus token
curl -i -X POST http://localhost:3000/mcp -H "Authorization: Bearer not.a.real.token" -H "Content-Type: application/json" -d '{}'
# → HTTP/1.1 401 Unauthorized
# → error_description="other"
```

In Claude Desktop, on a fresh chat:

> What services are available on the cib7 server?

Expected: 11 tools listed; `list_services` returns
`personRegistration` and `businessRegistration` with `mcpCallable: true`.

> Register Acme OÜ for me. I'm Bart Simpson, age 35, share capital €3000, board member Bart Simpson 38501010001 and Lisa Simpson 39102020002.

Expected: Claude calls `describe_service('businessRegistration')` →
`start_process('businessRegistration', { ... })`. DMN auto-approves
(share capital ≥ €2500 and applicant adult), approval email lands at
http://localhost:8025 (bring the inbox online with `docker compose
--profile dev up -d mailpit-ui`; the default profile keeps it
network-internal), and `list_my_processes` reports `state: COMPLETED`.

## Try the registration / invitation flow

> I want to register a new user — username lisa, email lisa@example.com, first name Lisa, last name Simpson.

Expected: Claude calls `send_account_invitation` with those four fields,
never asking for a password. The tool creates the Keycloak user with
`requiredActions: ["UPDATE_PASSWORD","VERIFY_EMAIL"]` and triggers the
magic-link email. Lisa opens Mailpit, clicks the link, sets her own
password in Keycloak's form, and lands in the SPA signed in.

> Where do I sign up?

Expected: Claude calls `get_signup_url` and returns the deep-linked
Keycloak registration URL. The user fills the form themselves.

> I forgot my password.

Expected: Claude calls `get_password_reset_url` and returns the
`kc_action=reset_credentials` deep link. The user resets it themselves.

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

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Claude Desktop says "not valid MCP server configuration" and skips cib7 | Old Claude Desktop release rejects the `{"url":...}` form. Use the `cib7-bridge.mjs` stdio bridge (see above). |
| mcp-remote bridge: `InsufficientScopeError: Policy 'Trusted Hosts' rejected request to client-registration service` | The bridge tried Dynamic Client Registration against Keycloak, which refuses anonymous DCR by default. The bridge already passes `--static-oauth-client-info '{"client_id":"cib7-mcp"}'` to skip DCR — if you see this error, your `mcp-remote` install is stale; `npm install -g mcp-remote@latest`. |
| mcp-remote bridge: `SyntaxError: Expected property name or '}' in JSON` | Windows shell stripped quotes from the inline JSON. Make sure Claude Desktop is invoking `cib7-bridge.mjs` via `node`, not piping through `cmd /c npx ...`. |
| OAuth callback says `invalid_scope: Invalid scopes: openid profile email` | mcp-remote requested scopes the realm doesn't define. The MCP service advertises only `openid` — rebuild it (`docker compose up -d --build mcp`) and clear `~/.mcp-auth/` so the bridge re-reads discovery. |
| `Authorization successful` in browser but the bridge errors with "Error POSTing to endpoint" | Stale state from before the per-request transport refactor. Restart the MCP container (`docker compose restart mcp`) and retry. |
| `list_services` returns `code: 'engine_unauthorized'` | The Bearer doesn't carry `aud=cib7-rest-api`. The audience mapper lives on the `cib7-rest-api-audience` client scope; confirm it's a default scope on `cib7-mcp` (admin UI → Clients → cib7-mcp → Client scopes → Default). |
| `start_process` returns `code: 'forbidden'` | The user's group doesn't grant CREATE_INSTANCE on the definition. `AuthorizationBootstrap.java` grants `ProcessDefinition:*` to the `applicant` group; self-registered users land in `/applicant` via `defaultGroups`. |
| `describe_service` returns `code: 'unknown_service'` | The manifest didn't make it into the container. `docker exec cib7-poc-mcp ls /app/services-spec/<service>/build/` should show `mcp-service.json` + `mcp-training.md`. If empty, the build context or `.dockerignore` is misconfigured — see `Dockerfile`. |
| `list_my_tasks` returns empty even though Cockpit shows tasks | The user is neither assigned nor a candidate for the open tasks. Check the BPMN's `camunda:assignee` / `candidateGroups` and the user's Keycloak group membership. |
| `complete_task` returns `INVALID_VARIABLES` with `"must NOT have additional properties"` | Claude included a field the per-task schema doesn't accept. Pass only the fields `get_form_schema` listed. |
| `send_account_invitation` returns `KEYCLOAK_ERROR: HTTP 401 Unauthorized` | cib7-backend's service-account token is stale, typically right after a realm re-import. Restart the MCP container to clear the in-process token cache. |
| Invitation email arrives but the link points at `http://keycloak:8080/...` | Realm `frontendUrl` attribute isn't set or the keycloak container wasn't rebuilt after editing the realm export. The export now ships `attributes.frontendUrl: "http://localhost:8180"`; re-import via `docker compose rm -sf keycloak && docker compose up -d keycloak`. |
| `query_user_history` returns `found: false` despite history existing | Prior process instances were wiped by an engine restart (in-memory H2). The `seed-history` compose service pre-populates Bart's history on a cold `docker compose up`; if you've restarted `cib7` since, re-run it: `docker compose start seed-history`. Permanent fix is in TODOS.md T1 (H2 → Postgres). |

## Files

- `package.json` — `@modelcontextprotocol/sdk`, `express`, `tsx`, `ajv`, `ajv-formats`, `jose`.
- `tsconfig.json` — strict TypeScript with `noEmit` (tsx interprets at runtime).
- `Dockerfile` — `node:20-alpine`, no multi-stage; `npm start` runs `tsx src/server.ts`. COPYs from repo root so it can include both `mcp/src` and `docs/business/services`.
- `cib7-bridge.mjs` — Node launcher used by Claude Desktop's `claude_desktop_config.json`. Imports `mcp-remote`'s entry directly with assembled `process.argv` to avoid shell-quoting issues.
- `src/server.ts` — Express + per-request MCP `Server` + `StreamableHTTPServerTransport`. Tool registry, `SERVER_INSTRUCTIONS` LLM playbook, AsyncLocalStorage bearer-context.
- `src/auth/verify.ts` — JOSE `jwtVerify` against Keycloak JWKS (signature + issuer). 401 + `WWW-Authenticate` on failure.
- `src/auth/identity.ts` — parse-only `preferred_username` extraction for query construction (assignee / startedBy).
- `src/engine/client.ts` — Bearer-forward `/engine-rest` fetch wrapper. `{ ok, status, code, message, retryable, data }` envelope. Stateless A2.
- `src/engine/variables.ts` — Plain JSON → Camunda `{ value, type }` envelope, schema-driven.
- `src/keycloak/admin.ts` — `cib7-backend` service-account token (client_credentials, 5-min in-process cache) + admin REST wrapper. Used by `send_account_invitation`.
- `src/services/manifest.ts` — Walks `/app/services-spec`, Ajv-compiles every schema, indexes by `formKey`.
