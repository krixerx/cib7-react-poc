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
   /api/public/**    ──▶ backend:8085        (clearance, registry, plate, permit)
   /api/documents/** ──▶ backend:8085        (move-pending, server-upload;
                                              bus injects X-Internal-Token)
```

Each integration is one declarative route file in `routes/`, all loaded via
`camel run --source-dir`. The engine carries only `BUS_URL`; the per-system
addresses and the `INTERNAL_TASK_TOKEN` secret live here on the bus. The
engine's `BusConfiguration.java` exposes `${busBaseUrl}` to BPMN — there is no
longer a Mail/Pdf/Backend config class per system.

## See it working

```bash
docker compose up -d --build esb        # build + start the bus
docker compose logs -f esb              # watch the bus
```

Then drive a process (`scripts/transport-demo-drive.py`, or the transport
vehicle "send back for corrections" branch). Each crossing prints an `[ESB] …`
line here before reaching the downstream system. Inspect the email inbox with
the dev profile:

```bash
docker compose --profile dev up -d mailpit-ui   # Mailpit UI at http://localhost:8025
```

## Adding an integration

Drop a new `routes/<name>.yaml` in — `--source-dir` auto-loads it, no Dockerfile
change — and point the engine connector at `${busBaseUrl}/<new-path>`. Gotcha:
for a fixed-path forward, `removeHeader CamelHttpPath` before the `to` (else the
inbound platform-http path is appended and the downstream 404s); for a
path-preserving prefix proxy, use `matchOnUriPrefix=true` and keep the header.

## Roadmap (not yet implemented)

- A canonical message envelope + transformation (so apps speak one neutral
  format to the bus), a mock external "Central Integration Platform" target,
  and a Kafka async event for the event-driven path.

## Revert

Reverting means restoring the per-system wiring the bus replaced — `MAIL_API_URL`
/ `PDF_API_URL` / `BACKEND_API_URL` on the engine, the `${mailApiBaseUrl}` etc.
beans, and the BPMN `X-Internal-Token` headers. The pre-ESB commit is the
simplest reference.
