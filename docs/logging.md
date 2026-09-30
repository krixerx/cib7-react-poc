# Centralised logging (Graylog)

**When to read this:** before adding a log statement you expect to find in
Graylog; when adding a new service to the stack; when the Graylog UI is
unreachable or a service's messages are missing; when changing the field
contract that searches and dashboards are built on.

Every service in this stack writes its logs twice: to stdout, so
`docker compose logs -f <service>` keeps working, and to **Graylog** as a
structured **GELF** message carrying the service name, the log level and — where
a request is attributable to a person — the Keycloak user id. Graylog itself is
not reachable from any network but the docker one; the web UI is bound to the
deployment host's loopback interface and reached over an SSH tunnel.

**Contents**
1. [Topology](#topology)
2. [The field contract](#the-field-contract)
3. [Access — SSH tunnel only](#access--ssh-tunnel-only)
4. [Per-service wiring](#per-service-wiring)
5. [Adding a log statement](#adding-a-log-statement)
6. [Adding a new service](#adding-a-new-service)
7. [What is not shipped](#what-is-not-shipped)
8. [Troubleshooting](#troubleshooting)

---

## Topology

```
cib7          ──GELF/UDP 12201──┐
backend       ──GELF/UDP 12201──┤
mcp           ──GELF/UDP 12201──┼──▶ graylog ──▶ opensearch   (message store)
pdf-renderer  ──GELF/UDP 12201──┤        │
esb           ──GELF/TCP 12201──┘        └────▶ graylog-mongodb (config store)

graylog-init  ──REST /api/system/inputs──▶ graylog   (creates the two inputs, once)

host 127.0.0.1:9900 ──▶ graylog:9000   (web UI, loopback only)
```

Three containers make up Graylog: the server itself, **OpenSearch** as the
message store, and **MongoDB** for Graylog's own configuration (users, streams,
inputs, dashboards). A fourth, `graylog-init`, is a one-shot `curl` sidecar.

Two decisions worth knowing:

- **OpenSearch directly, not Graylog Data Node.** Data Node boots into an
  interactive "preflight" setup UI that a human has to click through, which
  would break `docker compose up` as a one-command story. Wiring
  `GRAYLOG_ELASTICSEARCH_HOSTS` by hand skips preflight entirely
  (`GRAYLOG_SKIP_PREFLIGHT_CHECKS=true`).
- **Inputs are provisioned by a sidecar, not by hand.** Graylog stores inputs in
  MongoDB, so a fresh volume has nothing listening on 12201 and every shipper
  would quietly write into a closed port. `graylog/provision-inputs.sh` waits
  for the API, then creates `GELF UDP` and `GELF TCP` if they are missing. It is
  idempotent — it matches on input title — so it is safe on every start.

Both an SSH tunnel and a `docker compose down -v` are documented below because
those are the two operations you will actually perform.

## The field contract

Every message from every service carries these, and searches, streams and
dashboards should be built on them:

| Field | Example | Where it comes from |
|---|---|---|
| `service` | `cib7`, `backend`, `esb`, `mcp`, `pdf-renderer` | static per service, overridable with `LOG_SERVICE_NAME` |
| `level` | `3` (error), `6` (info) | GELF numeric **syslog severity**, not a log4j ordinal |
| `level_name` | `ERROR`, `INFO` | the readable level, so a search can say `level_name:ERROR` |
| `user_id` | `bart`, `homer` | Keycloak `preferred_username` of the caller — **present only when a user is attributable** |
| `host` | container hostname | the sending container |
| `short_message` | the log message | |
| `logger_name`, `thread_name` | | JVM services only |

`user_id` is absent, not empty, when there is no user behind a request. That is
the honest answer for the `esb` bus, the `pdf-renderer` facade, the backend's
`/api/public/**` token-link endpoints (the URL token is the credential, there is
no session) and the engine→backend calls authenticated with `X-Internal-Token`.
For a service-account token the value is the client id
(e.g. `service-account-cib7-business`), which is still the most useful thing
that can be said about the caller.

Useful searches once you are in the UI:

```
service:cib7 AND level_name:ERROR
user_id:bart
service:mcp AND _tool:start_process
service:backend AND level:<=4          # warnings and worse
```

## Access — SSH tunnel only

Nothing in the Graylog stack is published on a routable interface:

- the web UI is published as `127.0.0.1:9900` on the host — loopback only, so it
  is not reachable from the network even without a firewall rule;
- the GELF inputs are **not published at all**. Shippers are sibling containers
  and reach `graylog:12201` over the docker network;
- Traefik has no route to Graylog, so it is not exposed under the public
  ingress either.

Port 9900 rather than Graylog's native 9000 because RustFS already owns host
port 9000 in this stack.

From your laptop:

```bash
ssh -N -L 9900:127.0.0.1:9900 <user>@<host>
```

then open <http://localhost:9900> and log in as `admin` with the plaintext of
`GRAYLOG_ROOT_PASSWORD_SHA2` (`admin` by default in the dev compose file).

If you tunnel to a different local port, change `GRAYLOG_HTTP_EXTERNAL_URI` to
match — Graylog hands that URI to the browser for its own API calls, so a
mismatch shows up as a UI that loads and then fails every request.

For a local `docker compose up` on your own machine no tunnel is needed:
<http://localhost:9900> is already the loopback address the port is bound to.

## Per-service wiring

Three different mechanisms, because the three runtimes have different
constraints. All five produce the same fields.

### cib7 and backend (Spring Boot)

`de.siegmar:logback-gelf` provides the appender;
`src/main/resources/logback-spring.xml` configures it. Spring Boot can *format*
GELF onto the console since 3.4 but ships no GELF *transport*, which is what the
library adds.

The appender only exists under the **`graylog` Spring profile**
(`SPRING_PROFILES_ACTIVE=graylog`, set in `docker-compose.yml`). Without the
profile the service logs to the console only — which is what a bare
`cd cib7 && mvn spring-boot:run` on a laptop wants, since `graylog` does not
resolve there.

UDP rather than TCP: logback-gelf chunks and gzips datagrams itself, so long
stack traces survive, and a fire-and-forget socket cannot stall a request
thread when Graylog is wedged.

`user_id` comes from the SLF4J **MDC**. `MdcUserFilter` (one per module, under
`…/logging/`) reads the authenticated principal and puts the username in the MDC
for the duration of the request; the GELF encoder copies MDC entries into
additional fields. The filter is registered at servlet order `-99`, one step
**after** Spring Security's filter chain at `-100`, which is what guarantees the
`SecurityContext` is populated by the time it runs.

### esb (Apache Camel JBang)

Camel JBang logs through Log4j2 and owns that setup itself, so the bus gets its
configuration through the supported flag:

```
camel run --source-dir=/routes --logging-config-path=/etc/camel/log4j2-graylog.xml
```

`esb/log4j2-graylog.xml` adds a `Socket` appender with `GelfLayout` next to the
console one. Both classes ship inside `log4j-core`, so this needs no extra jar
on a JBang classpath that is awkward to extend.

This is the one service on **TCP**: Log4j2's `GelfLayout` does not implement
GELF's UDP chunking protocol, so an oversized datagram would simply be lost. A
null-delimited TCP stream has no size ceiling — hence the second input.

### mcp and pdf-renderer (Node)

A ~60-line GELF UDP module each: `mcp/src/logging/gelf.ts` (TypeScript, with
unit tests on the payload shape) and `pdf-renderer/gelf.js` (CommonJS). Written
by hand rather than taken from npm because the protocol we need is "JSON object
in a datagram", and the MCP sidecar's dependency list is deliberately short —
it forwards user Bearer tokens, so every package in it sits in the trust path.

They are duplicated rather than shared: the two sidecars have no build step and
no common package, and a workspace for 60 lines is a worse trade. **The field
contract above is the thing that must stay in sync**, and it is written down
here rather than in either copy.

Neither implements UDP chunking, so messages are truncated to fit one datagram
and marked `_truncated: true` when that happens.

In `mcp`, `user_id` comes from the per-request `AsyncLocalStorage` that already
holds the caller's Bearer. The logger takes a resolver callback set from
`server.ts` so the dependency points one way. Every MCP tool call logs one line
with the tool name and the caller, which makes Graylog the answer to "who asked
the assistant to do what, and did it work".

## Adding a log statement

Nothing special — use the module's normal logger and it gets the fields for
free:

```java
// cib7 / backend
private static final Logger LOG = LoggerFactory.getLogger(Foo.class);
LOG.info("Moved {} documents to process instance {}", count, processInstanceId);
```

```ts
// mcp
import { log } from './logging/gelf.js';
log.info('draft saved', { tool: 'save_draft', taskId });
```

Caller-supplied fields in the Node services are underscore-prefixed on the wire
(`{ tool: 'x' }` arrives as `_tool`), because Graylog discards custom fields that
are not prefixed.

Do **not** log request bodies, tokens, or personal data from the forms. Graylog
keeps messages for as long as the index retention allows, and this stack handles
civil ids and scanned identity documents.

## Adding a new service

1. Pick the mechanism that matches the runtime (one of the three above).
2. Send `service`, `level`, `level_name`, and `user_id` when a user is known —
   same names, same meaning. A new field name breaks existing searches.
3. Point it at `graylog:12201` over the docker network. Do not publish a port.
4. Add `GRAYLOG_HOST`, the port, and `LOG_SERVICE_NAME` to the service's
   `environment:` block in `docker-compose.yml`.
5. No new Graylog input is needed — reuse the UDP or TCP one.

## What is not shipped

- **Infrastructure containers**: Keycloak, Mailpit, Gotenberg, RustFS,
  OpenSearch, MongoDB and Traefik log to stdout only. They are third-party
  images, and instrumenting them would mean either a log-driver change on the
  docker daemon or a sidecar per container. `docker compose logs <service>` is
  the tool for those.
- **The frontend and mobile containers**: both are nginx serving static files.
  Browser-side errors never reach the server, so a central log would only hold
  access lines.
- **Engine incidents are still Cockpit's job.** A failed connector, DMN or
  FreeMarker template surfaces as an engine incident at
  `/camunda/app/cockpit/` with the variables and the failed activity attached.
  Graylog has the log line; Cockpit has the retryable job. Use both.

## Troubleshooting

**The UI does not load.** Check the tunnel first (`ssh -N -L …` must stay
running), then `docker compose logs graylog`. On a cold start with an empty
OpenSearch volume, Graylog takes a couple of minutes before it answers.

**The UI loads but every request fails.** `GRAYLOG_HTTP_EXTERNAL_URI` does not
match the URL in the browser's address bar. Set it to the local end of your
tunnel.

**No messages at all, from anything.** The inputs were probably never created:
`docker compose logs graylog-init`. The script echoes every API response, so a
rejection is visible there. Re-run it on its own with
`docker compose up graylog-init`.

**One service is missing.** Confirm the input exists (System → Inputs shows a
message counter), then check that service's console output — every shipper here
logs its own transport failures to stdout. For `cib7` and `backend`, the usual
cause is a missing `SPRING_PROFILES_ACTIVE=graylog`.

**Messages arrive without `user_id`.** Expected for the cases listed under
[the field contract](#the-field-contract). For a request that really should have
a user, check that the call carried a Bearer at all — an unauthenticated request
has no user to name.

**OpenSearch will not start.** `node.store.allow_mmap=false` is already set so
the host's `vm.max_map_count` sysctl does not matter. If it still fails, the
usual cause is memory: OpenSearch, Graylog and MongoDB together want roughly
2 GB on top of the rest of the stack.

**Start over.** `docker compose down -v` drops the named volumes, which wipes
the message store, Graylog's configuration and the provisioned inputs. The next
`up` recreates the inputs.
