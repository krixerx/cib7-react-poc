# esb — integration bus (Apache Camel)

A minimal **communication bus** in front of the engine's outbound integrations,
standing in for the RFP's *"all integration must cross the Central Integration
Platform"* mandate. Built on **Apache Camel JBang** running declarative **YAML
routes** — there is no Java route code.

## Why it exists

Today the CIB seven engine calls external systems point-to-point through its
BPMN `http-connector` (Mailpit, pdf-renderer, the backend). The RFP requires the
opposite: each application talks to a central bus, and the bus talks to the real
systems. This service is the bus.

## What it carries today

**All** of the engine's outbound HTTP. The engine knows one address —
`${busBaseUrl}` = `http://esb:8080` — and the bus routes each path to the real
downstream system:

```
cib7 engine ──POST ${busBaseUrl}/…──▶ [ Camel routes ] ──▶ downstream
   /api/v1/send      ──▶ mailpit:8025
   /render           ──▶ pdf-renderer:8088   (response flows back)
   /api/public/**    ──▶ backend:8085        (vehicle catalog lookup)
   /api/internal/**  ──▶ backend:8085        (documents, case index; bus
                                              injects X-Internal-Token)
```

Each integration is one declarative route file in `routes/`, all loaded via
`camel run --source-dir`. The engine carries only `BUS_URL`; the per-system
addresses and the `INTERNAL_TASK_TOKEN` secret live here on the bus. The
engine's `BusConfiguration.java` exposes `${busBaseUrl}` to BPMN — there is no
longer a Mail/Pdf/Backend config class per system.

## Authentication

Every route starts with `to: direct:bus-auth` (`routes/bus-auth.yaml`). A call
passes only with `X-Bus-Token` equal to the bus's `BUS_TOKEN` env var, which the
engine sends on every connector call; anything else gets a fixed `401` JSON body
and is not forwarded. The header is stripped before the call leaves the bus.
`BUS_TOKEN` has no default here: unset or empty, the bus refuses everything.
The comparison is plain string equality because the simple language has no
constant-time compare; the bus is reachable only on the internal network.

A new route must call `direct:bus-auth` first. `X-Internal-Token` is injected
only on the `/api/internal` route, so it reaches the backend only for callers
that passed this check.

## See it working

```bash
docker compose up -d --build esb        # build + start the bus
docker compose logs -f esb              # watch the bus
```

Then drive a process (for example the vehicle registration "send back for
corrections" branch). Each crossing prints an `[ESB] …`
line here before reaching the downstream system. Inspect the email inbox with
the dev profile:

```bash
docker compose --profile dev up -d mailpit-ui   # Mailpit UI at http://localhost:8025
```

## Adding an integration

Drop a new `routes/<name>.yaml` in — `--source-dir` auto-loads it, no Dockerfile
change — and point the engine connector at `${busBaseUrl}/<new-path>`. Start
its steps with `- to: "direct:bus-auth"`. Gotcha:
for a fixed-path forward, `removeHeader CamelHttpPath` before the `to` (else the
inbound platform-http path is appended and the downstream 404s); for a
path-preserving prefix proxy, use `matchOnUriPrefix=true` and keep the header.

### Core routes and service pack routes

The four routes here (`bus-auth`, `backend-*`, `notification-bus`, `pdf-bus`)
are core: every deployment needs them. A service pack's own integrations (a
customer's registry, payment provider) go into the same `/routes` directory,
because `camel run --source-dir` reads one directory and takes no file list
alongside it. A pack adds them with a bind mount per file or a thin image layer
(`COPY routes/*.yaml /routes/`). Pack route ids and file names start with
`pack-` so they can never collide with a core route, and a pack route listens
under `/pack/<name>`, where no core route does. The full rules, checked by
`scripts/pack-check.sh` (`PackBusTest`), are in
[`docs/platform-api.md`](../docs/platform-api.md#formats): `direct:bus-auth`
first, only outside `http(s)` systems as targets, only `PACK_*` environment
variables, never the core's `X-Internal-Token` or `X-Bus-Token`, no code. How
a pack's routes get into the image is part of the pack repository templates
(S36); the reference pack has none yet.

## Logging

The bus logs to the console and to Graylog. Camel JBang owns its own Log4j2
setup, so the configuration comes in through the supported flag rather than a
classpath file:

```
camel run --source-dir=/routes --logging-config-path=/etc/camel/log4j2-graylog.xml
```

`log4j2-graylog.xml` keeps the console appender — `docker compose logs -f esb`
must stay useful — and adds a `Socket` appender with `GelfLayout` pointing at
`graylog:12201`. Both classes ship inside `log4j-core`, so nothing has to be
added to a JBang classpath.

This is the only service in the stack that ships GELF over **TCP**: Log4j2's
`GelfLayout` does not implement GELF's UDP chunking protocol, so a large message
would be silently lost on UDP. `ignoreExceptions="true"` on the appender is what
keeps a route running when Graylog is down — the failure is printed to the
console and the event is dropped.

Messages carry `service:esb` and `level_name`, but no `user_id`: the bus forwards
machine-to-machine integration calls, so there is no Keycloak user behind them.
See [`../docs/logging.md`](../docs/logging.md).

## Roadmap (not yet implemented)

- A canonical message envelope + transformation (so apps speak one neutral
  format to the bus), a mock external "Central Integration Platform" target,
  and a Kafka async event for the event-driven path.

## Revert

Reverting means restoring the per-system wiring the bus replaced — `MAIL_API_URL`
/ `PDF_API_URL` / `BACKEND_API_URL` on the engine, the `${mailApiBaseUrl}` etc.
beans, and the BPMN `X-Internal-Token` headers. The pre-ESB commit is the
simplest reference.
