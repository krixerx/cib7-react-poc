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

## What it carries today (Stage 1)

One flow — **outbound email**:

```
cib7 engine ──POST http://esb:8080/api/v1/send──▶ [ Camel route ] ──▶ mailpit:8025
                                                         │
                                                         └─ logs every crossing (audit)
```

The engine's `MAIL_API_URL` points at `http://esb:8080` (see
`docker-compose.yml`) instead of Mailpit directly. The route
(`routes/notification-route.yaml`) logs the crossing and forwards the request
verbatim; Mailpit's response flows back to the engine. No engine code or BPMN
was changed — only the one env var was repointed, so it is fully reversible.

## See it working

```bash
docker compose up -d --build esb        # build + start the bus
docker compose logs -f esb              # watch the bus
```

Then drive any process to a state that sends an email (e.g. the transport
vehicle "send back for corrections" branch, or `scripts/transport-demo-drive.py`).
Each email now prints an `[ESB] notification crossing the bus …` line here before
landing in Mailpit. Inspect the inbox with the dev profile:

```bash
docker compose --profile dev up -d mailpit-ui   # Mailpit UI at http://localhost:8025
```

## Roadmap (not yet implemented)

- **Stage 2/3** — route the PDF (`/render`) and backend (`/api/**`) calls through
  the bus too, each as one more YAML route; the bus injects the backend
  `X-Internal-Token`.
- **Stage 4** — collapse the engine's `Mail`/`Pdf`/`Backend` config classes into a
  single `busBaseUrl`; downstream addresses + the secret live here on the bus.
- Later — canonical message envelope + transformation, a mock external
  "Central Integration Platform" target, and a Kafka async event.

## Revert

Point `MAIL_API_URL` back to `http://mailpit:8025` in `docker-compose.yml` and
the engine bypasses the bus again.
