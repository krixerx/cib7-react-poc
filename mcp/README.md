# MCP sidecar (`mcp/`)

Standalone MCP microservice for the CIB seven POC. Mirrors the
`pdf-renderer/` sidecar pattern — Node 20 + Express + a tiny TypeScript
entry — and runs as its own docker-compose service.

**Current state: T1 + T2 + T3 + T5/T6 + T9 done. Eight tools wired against `personRegistration`. The applicant round trip is fully MCP-callable.**

- T1: PKCE-loopback challenge surface (`/mcp` returns 401 + WWW-Authenticate, resource metadata at `/.well-known/oauth-protected-resource`).
- T2: `cib7-rest-api-audience` client scope wired so JWTs for `cib7-mcp` carry the right `aud`.
- T3: stateless Bearer-proxy in place (`engine/client.ts`). Forwards Claude Desktop's Bearer to `/engine-rest`, maps responses to `{ ok, status, code, message, retryable, data }` envelope.
- T5/T6: manifests under `docs/business/services/<id>/build/`, Ajv validation, three start-side tools.
- T9: identity decoding (`auth/identity.ts`), per-task schemas in the manifest (`userTasks` array), five additional tools that finish the applicant round trip.

### The eight tools

| Tool | Engine endpoint | Purpose |
|---|---|---|
| `list_services` | `GET /process-definition?latestVersion=true` | What's deployed; decorated with `mcpCallable` flag. |
| `describe_service(key)` | (none — reads manifest) | Variable schema + LLM training markdown. |
| `start_process(key, variables)` | `POST /process-definition/key/<k>/start` | Ajv-validate → Camunda vars → engine. |
| `list_my_tasks` | `GET /task?assignee=<me>` | Tasks waiting for the authenticated user, decorated with service + audience. |
| `get_form_schema(taskId)` | `GET /task/<id>` | Looks up the task's formKey in the manifest registry, returns the per-task JSON Schema + audience + description. |
| `complete_task(taskId, variables)` | `POST /task/<id>/complete` | Ajv-validate against per-task schema → Camunda vars → engine. |
| `list_my_processes(processInstanceId?)` | `GET /history/process-instance?startedBy=<me>` | Newest first, decorated with state (ACTIVE / COMPLETED / etc.). |
| `query_user_history(variableName)` | Two-step: instances → variables | Most recent value the authenticated user ever entered for that variable. Used for autofill in T15+. |

The username `<me>` is decoded from the Bearer's `preferred_username` locally (no signature check; that's the engine's job).

The remaining tasks from the eng-review build on this: T4 (ROPC seed for autofill demo), T8+T9 regression tests, T11+T12 hardening, T14 (auto-emit manifests from `/service-builder`), T15 (business-registration spec), T16 (`system_context` prompt), T17–T18 (docs).

## Endpoints

| Path | Purpose |
|---|---|
| `GET /.well-known/oauth-protected-resource` | RFC 9728 / MCP auth-spec resource metadata. Tells Claude Desktop which authorization server to use (Keycloak). |
| `* /mcp` | MCP Streamable HTTP transport. 401 + `WWW-Authenticate: Bearer` when no token. |
| `GET /health` | Liveness probe for docker-compose. |

## How auth works (T1 scope)

1. Claude Desktop calls `/mcp`. No Bearer → 401 + `WWW-Authenticate: Bearer resource_metadata="…"`.
2. Claude Desktop fetches the resource metadata → learns the authorization server is Keycloak.
3. Claude Desktop fetches Keycloak's `/.well-known/openid-configuration` → learns the auth endpoint, PKCE support, scopes.
4. Claude Desktop runs the PKCE-loopback flow: pops a browser, user logs in, redirect back to `http://127.0.0.1:<port>/callback?code=…`.
5. Claude Desktop exchanges the code for an access token, attaches it to all subsequent `/mcp` calls.
6. **T1 scope:** the MCP service accepts ANY Bearer token without verification and decodes the `preferred_username` claim for the `echo` reply. **Real validation happens at the engine in T2+** under the stateless Bearer-proxy model (eng-review decision A2).

## Build & run (docker-compose)

```bash
docker compose up --build mcp
```

The sidecar exposes port 8090 internally; nginx proxies it at
`http://localhost:3000/mcp`.

## Verify T1 end-to-end

See `/docs/mcp.md` and the verification block in the parent README. The TL;DR:

1. `docker compose up --build` — all containers healthy.
2. `curl http://localhost:3000/mcp` → 401 + `WWW-Authenticate` header.
3. `curl http://localhost:3000/.well-known/oauth-protected-resource` → JSON pointing at Keycloak.
4. Add this entry to your `claude_desktop_config.json`:
   ```json
   {
     "mcpServers": {
       "cib7": { "url": "http://localhost:3000/mcp" }
     }
   }
   ```
5. Restart Claude Desktop. The MCP server should appear in the connection list. Claude pops a browser to Keycloak; log in as `bart` / `bart`.
6. Ask Claude: **"What services are available on the cib7 server?"** Expected reply: a list including `personRegistration` with `mcpCallable: true` (it has a manifest) for Bart; Homer sees the full deployed set.
7. Ask Claude: **"Describe personRegistration."** Expected reply: name, description, JSON Schema for the four required variables (`firstName`, `lastName`, `age`, `objectId`), and the training markdown that explains the auto-approval rule and the after-start flow.
8. Ask Claude: **"Start a personRegistration for Bart Simpson, age 30, product ID 1."** Expected behavior:
   - Claude validates the variables match the schema (no missing/wrong-typed fields).
   - Returns `{ ok: true, processInstanceId: '<uuid>', ended: false, ... }`.
   - A new instance appears in CIB seven Cockpit (`http://localhost:8080/camunda/app/cockpit/`) with the variables pre-filled.
   - The applicant ("Submit personal details") task is open in the React portal (`http://localhost:3000`) ready for Bart to confirm.
9. Optional: ask Claude to **start a personRegistration with an invalid age** (e.g., 200). Expected: `{ ok: false, code: 'INVALID_VARIABLES', issues: [...] }` with the Ajv-generated error pointing at the `maximum: 130` constraint. No engine call is made.

### Full round trip (T9 — applicant completes their own task via MCP)

After step 8 a process instance is sitting at "Submit personal details" with the variables pre-filled. To close the loop without leaving the chat:

10. **"What's pending for me?"** → Claude calls `list_my_tasks` → returns one entry with `formKey: 'personal-details'`, `service: 'personRegistration'`, `audience: 'applicant'`.
11. **"Confirm it for me."** → Claude calls `get_form_schema(taskId)` to know the shape, then `complete_task(taskId, { firstName, lastName, age, objectId })` with the same values from start_process. Engine advances the process. If you started with an adult applicant and an objectId that resolves to a cheap product, the DMN auto-approves and the process ends. Otherwise a `review-application` task is created for the civil servant.
12. **"What's the status of my registration?"** → Claude calls `list_my_processes` → returns the instance with `state: 'COMPLETED'` (auto-approved) or `state: 'ACTIVE'` (waiting on civil-servant review).
13. **Optional civil-servant side:** log into Claude as Homer (re-run the OAuth flow with `homer`/`homer`). Then **"What's waiting for me?"** → Claude calls `list_my_tasks` → returns `review-application` tasks. **"Approve task abc..."** → Claude calls `complete_task(abc, { decision: 'approve' })`. Process completes.
14. **History autofill check** (forward-looking to T15): **"What's my first name on record?"** → Claude calls `query_user_history('firstName')` → returns `{ found: true, value: 'Bart', sourceProcessInstanceId: '...' }`. This is the building block the businessRegistration demo uses to autofill applicant details without re-prompting.

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| Browser doesn't pop on first MCP call | Claude Desktop's MCP OAuth support isn't picking up the 401 challenge — check Claude Desktop release notes; fallback is OAuth2 device flow (flip `directAccessGrantsEnabled` to `true` on `cib7-mcp` and use device grant). |
| `list_services` returns `code: 'engine_unauthorized'` | T2 audience mapper isn't on the token. Keycloak admin UI → Client scopes → `cib7-rest-api-audience` → Mappers → confirm; then Clients → `cib7-mcp` → Client scopes tab → confirm `cib7-rest-api-audience` is under Default. |
| `start_process` returns `code: 'forbidden'` | Bart's authorizations don't grant CREATE_INSTANCE on the definition. Check `AuthorizationBootstrap.java`. The current bootstrap covers `personRegistration` specifically; widening to `ProcessDefinition:*` is T9. |
| `describe_service` returns `code: 'unknown_service'` | The manifest didn't make it into the container. Run `docker exec cib7-poc-mcp ls /app/services-spec/person-registration/build/` and confirm both files are present. If empty, the build context or .dockerignore is misconfigured. |
| `list_services` shows `mcpCallable: false` for everything | The mcp container started before the manifests were COPYed (look at `manifests:` in container startup logs — should list `personRegistration`). Rebuild with `docker compose up --build mcp`. |
| `list_my_tasks` returns empty even though Cockpit shows tasks | The user is not in any candidate group for the open tasks and the task is not assigned to them. Check the BPMN's `candidateGroups` and the user's group memberships in Keycloak admin. For personRegistration, Bart (in `applicant`) sees the personal-details task; Homer (in `civil-servant`) sees the review-application task after the DMN routes there. |
| `complete_task` returns `code: 'INVALID_VARIABLES'` with `"must NOT have additional properties"` | Claude included a field the task schema doesn't accept (e.g., the entire start-variables blob on the review-application task, which only accepts `decision` + optional `sendBackReason`). Pass only the fields the get_form_schema response listed. |
| `query_user_history` returns `found: false` despite history existing | Bart's prior process instances may have been wiped by an engine restart (in-memory H2). Restart `mcp` after the engine — or wait until T4 lands the seed compose service. |

## Files

- `package.json` — `@modelcontextprotocol/sdk`, `express`, `tsx` runner.
- `tsconfig.json` — strict TypeScript with `noEmit` (tsx interprets at runtime).
- `Dockerfile` — `node:20-alpine`, no multi-stage; `npm start` runs `tsx src/server.ts`.
- `src/server.ts` — Express + MCP transport, 401 challenge, AsyncLocalStorage bridge for the Bearer header, tool registry. One tool (`list_services`) at T3.
- `src/engine/client.ts` — stateless `/engine-rest` fetch wrapper. Forwards the user's Bearer, maps responses to `{ ok, status, code, message, retryable, data }`. Decision A2: never validates JWTs locally, never refreshes.
- `.dockerignore` — keep `node_modules/` out of the build context.
