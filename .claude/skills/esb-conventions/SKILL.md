---
name: esb-conventions
description: Rules for writing, changing or reviewing the Apache Camel integration bus in esb/ (Camel JBang YAML routes, no build step), including pack routes. Use before touching esb/routes/*.yaml or esb/Dockerfile, when adding an outbound integration, when a bus call fails or answers the wrong thing, and when running /review.
---

# Integration bus conventions

Every outbound call the engine makes crosses `esb/`: Camel JBang, YAML routes, no Java. The engine
knows one address, `${busBaseUrl}`. Editing a route is editing a file and restarting the container.
`esb/README.md` and each route's header comment carry the longer reasoning.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule | Where |
|---|---|---|---|
| E1 | BLOCKER P1 | A route's first step is `direct:bus-auth`; an unset, empty or wrong `X-Bus-Token` answers 401 | `esb/routes/bus-auth.yaml`; `docs/security.md` §9 |
| E2 | BLOCKER P1 | `X-Internal-Token` is set only on the `/api/internal` route and removed on `/api/public` | `esb/routes/backend-route.yaml` |
| E3 | BLOCKER P1 | A pack route uses the `pack-` prefix, `/pack/<name>` and `PACK_*` env only, and never touches core tokens | `esb/README.md`; `docs/platform-api.md`; `PackBusTest` |
| E4 | BLOCKER P2 | A fixed-path forward removes `CamelHttpPath`; a prefix proxy (`matchOnUriPrefix=true`) keeps it | [E4](#e4) |
| E5 | BLOCKER P2 | An env-driven target is `toD: "${env.X}?bridgeEndpoint=true"`; `{{env:X}}` does not resolve here | `esb/routes/notification-route.yaml` |
| E6 | BLOCKER P2 | Every producer carries `?bridgeEndpoint=true` | `esb/routes/pdf-route.yaml` |
| E7 | BLOCKER P2 | A new integration is a new `routes/<name>.yaml`, reached at `${busBaseUrl}/<path>`; no config class in the engine | `esb/README.md` |
| E8 | BLOCKER P3 | Every crossing logs one `[ESB]` line naming the target; a rejection logs at WARN | [E8](#e8) |

## Details

### E4

**BLOCKER P2.** `platform-http` puts the inbound path in `CamelHttpPath`, and the http producer
appends it. A fixed-path forward left with the header duplicates the path. A prefix proxy needs it,
because that header is the part of the path being forwarded.

### E8

**BLOCKER P3.** `docker compose logs -f esb` is how a crossing is watched, and logs ship to Graylog
with the field `service=esb` and no `user_id`. Mail and PDF routes log `correlationId=${exchangeId}`.
Never log a token or a payload.

## Unverified

No route sets `throwExceptionOnFailure`, and no test proves what a caller receives when a downstream
answers 4xx or 5xx. Check that before relying on a status passing through the bus.
