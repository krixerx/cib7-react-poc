---
name: mcp-conventions
description: "Rules for writing, changing or reviewing the MCP sidecar in mcp/ (TypeScript, stateless Bearer proxy): tools, the caller's Bearer, manifests, the error envelope and untrusted data. Use before adding or changing a tool, touching auth or guards in mcp/src, and when running /review."
---

# MCP conventions

`mcp/` lets an agent drive a deployment as a real Keycloak user. Tools are defined inline in
`mcp/src/server.ts`. `docs/mcp.md` is the full reference; this is the checklist.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule |
|---|---|---|
| MCP1 | BLOCKER P1 | Forward the caller's own Bearer to `/engine-rest`. There is no AI service account for engine calls; the one exception is `send_account_invitation`, which uses `cib7-backend` for the Keycloak admin API and is documented in `docs/mcp.md` |
| MCP2 | BLOCKER P1 | A JWT is fully verified at the door (`auth/verify.ts`); nothing reaches a tool without one, because some tools never reach the engine |
| MCP3 | BLOCKER P1 | Never ask for or accept a password; identity flows go through URLs and Keycloak |
| MCP4 | BLOCKER P1 | Anything a user typed goes under `untrustedData` with `withUntrustedData`; server guidance stays in trusted fields |
| MCP5 | BLOCKER P2 | No per-user state: a fresh Server and Transport per HTTP request, and no refresh tokens. The only cache is the service-account token |
| MCP6 | BLOCKER P2 | Failures return the typed envelope `{ok,status,code,message,retryable}` with `isError`; no silent swallowing |
| MCP7 | BLOCKER P2 | A new tool: input schema in `ListToolsRequestSchema`, a handler with the same envelope, and its endpoint in the `docs/mcp.md` table. The LLM sees only what the schema says |
| MCP8 | BLOCKER P2 | Service schemas come from the pack's generated manifests; do not name a pack's service in `mcp/src` |
| MCP9 | WARNING | Logic worth testing lives in a module with a vitest file next to it; `server.ts` and `engine/client.ts` have no tests of their own |
