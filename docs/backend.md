# Backend

**When to read this:** before editing anything under `backend/`; when changing
the BPMN, the engine config, the connector wiring, or `pom.xml`; when
investigating a startup failure or a service-task execution issue.

**Contents**
1. [Stack](#stack)
2. [File layout](#file-layout)
3. [Spring Boot wiring](#spring-boot-wiring)
4. [Engine configuration (`application.yaml`)](#engine-configuration-applicationyaml)
5. [BPMN files](#bpmn-files)
6. [Connect plugin and connector](#connect-plugin-and-connector)
7. [Maven, JDK, and the vendored connector](#maven-jdk-and-the-vendored-connector)
8. [Run, build, package](#run-build-package)
9. [Conventions](#conventions)

---

## Stack

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5 |
| Process engine | CIB seven 2.1 (Camunda 7 fork) via `cibseven-bpm-spring-boot-starter-rest` |
| Database | H2, in-memory (no `spring.datasource` configured) |
| Connectors | `cibseven-engine-plugin-connect` + `rest-datasonnet-connector` (vendored in `lib/`) |
| Build | Maven, `spring-boot-maven-plugin` |

## File layout

```
backend/
├── pom.xml
├── Dockerfile
└── src/main/
    ├── java/com/poc/cib7/
    │   ├── Cib7PocApplication.java        — @SpringBootApplication entry point
    │   └── ConnectorConfiguration.java    — registers ConnectProcessEnginePlugin
    └── resources/
        ├── application.yaml               — engine + auto-deploy config
        └── processes/
            └── person-registration.bpmn   — auto-deployed on startup
```

There are intentionally only two Java classes. Anything more complex (delegate
classes, listeners, custom REST controllers) does not exist yet — when adding
one, follow Spring Boot conventions (a `@Component` / `@Configuration` class
under `com.poc.cib7`).

## Spring Boot wiring

### `Cib7PocApplication.java`

A bare `@SpringBootApplication`. The CIB seven starter embeds the engine and
exposes `/engine-rest`. No additional configuration is needed here.

### `ConnectorConfiguration.java`

Declares a single `@Bean` of type `ConnectProcessEnginePlugin`. The CIB seven
starter discovers all `ProcessEnginePlugin` beans and wires them into the
engine. Without this bean, `<camunda:connector>` elements in BPMN are ignored
and the "Get price" service task fails at deploy/runtime.

If you add another engine plugin (e.g. an LDAP identity provider), add it as
another `@Bean` in the same `@Configuration` class or split per concern.

## Engine configuration (`application.yaml`)

```yaml
server:
  port: 8080

camunda.bpm:
  deployment-resource-pattern: classpath*:**/*.bpmn
  database:
    schema-update: true
```

| Key | Why |
|---|---|
| `server.port: 8080` | Hard-coded so the SPA proxy targets are stable in both dev and Docker |
| `camunda.bpm.deployment-resource-pattern` | Auto-deploys every `*.bpmn` on the classpath at startup — no Java code needed |
| `camunda.bpm.database.schema-update: true` | Lets the engine create its tables on first start (required for the in-memory H2 lifecycle) |
| *(no `spring.datasource`)* | Triggers Spring Boot's H2 auto-config — in-memory DB, state wiped on restart |

## BPMN files

**Location.** `backend/src/main/resources/processes/`. Anything matching
`classpath*:**/*.bpmn` is auto-deployed on startup, but keep BPMN under
`processes/` for sanity.

**Repo convention: one file per process** (one top-level `<bpmn:process>`).
The engine does not enforce this — the auto-deploy pattern just scans every
`*.bpmn` and a single file can technically hold multiple processes — but we
keep it one-to-one so the file name matches the process and diffs stay small.

**Editing.** Use the Camunda Modeler (it understands the `camunda:` namespace
and DI). Hand-editing the XML is fine for small changes; keep the
`<bpmndi:BPMNDiagram>` in sync so the diagram still opens.

**Namespace.** Use the `camunda:` namespace
(`xmlns:camunda="http://camunda.org/schema/1.0/bpmn"`), not `cib:` — CIB seven
2.1 uses `camunda:` exactly like Camunda 7. The spec's §5.3 is inaccurate on
this point; see the deviations table in the top-level
[`README.md`](../README.md#deviations-from-the-spec).

**User-task → React-form contract.** A user task is bound to a React form by
`camunda:formKey="react:<form-id>"`. The form id (everything after `react:`)
must match a key in `frontend/src/forms/registry.ts`. There is no publish-time
manifest validation — if the key is wrong, the TaskDetail page renders an
error. See [`frontend.md` — Forms](frontend.md#forms).

**Variables.** Plain typed variables (`firstName: String`, `objectId: String`,
`price: Double`, `decision: String`, …), not a single `json` Spin variable.
The variable name + type form part of the form contract.

**Existing process — `person-registration.bpmn`.**

```
StartEvent_1
  → Task_SubmitDetails   userTask    formKey="react:personal-details"
  → Task_GetPrice        serviceTask asyncBefore=true, connector="rest-datasonnet"
  → Task_Review          userTask    formKey="react:review-application"
  → Gateway_Decision     exclusiveGateway, branches on ${decision == "approve"}
  → EndEvent_Approved | EndEvent_Rejected
```

Process variables and which step writes each: see the header comment inside
the BPMN file.

## Connect plugin and connector

The Connect plugin (registered in `ConnectorConfiguration.java`) lets the
engine parse `<camunda:connector>` extension elements. The `rest-datasonnet`
connector is a Connect SPI implementation that does:

1. HTTP request to a configured URL.
2. Optional DataSonnet transformation of request body / response body.
3. Output the mapped result into a process variable.

It is wired inline inside `Task_GetPrice`'s `<bpmn:extensionElements>`:

```xml
<camunda:connector>
  <camunda:connectorId>rest-datasonnet</camunda:connectorId>
  <camunda:inputOutput>
    <camunda:inputParameter name="url">https://api.restful-api.dev/objects/${objectId}</camunda:inputParameter>
    <camunda:inputParameter name="method">GET</camunda:inputParameter>
    <camunda:inputParameter name="source">{}</camunda:inputParameter>
    <camunda:inputParameter name="responseMapping"><![CDATA[/** DataSonnet version=2.0 */
payload.data.price
]]></camunda:inputParameter>
    <camunda:outputParameter name="price">${result}</camunda:outputParameter>
    <camunda:outputParameter name="restOutcome">${restOutcome}</camunda:outputParameter>
  </camunda:inputOutput>
</camunda:connector>
```

The `asyncBefore="true"` flag means the service task runs in the job executor:
after the previous user task is completed, the call happens on a background
thread; the next user task appears a moment later. The Tasks page **Refresh**
button is the polling mechanism.

## Maven, JDK, and the vendored connector

### JDK 17

CIB seven 2.1 + Spring Boot 3.5 require **JDK 17**. The default `java` on the
developer's PATH may be JDK 11 — set `JAVA_HOME` to a JDK 17 before running
Maven. The Docker build uses an `eclipse-temurin:17` base image so it does not
depend on the host JDK.

### Vendored connector — `lib/`

`rest-datasonnet-connector` is not yet published to a public Maven repository.
It is vendored in `lib/` (repo root) in standard Maven repository layout. The
backend POM declares `lib/` as an extra `<repository>` and the connector as a
normal `<dependency>`:

```xml
<repository>
  <id>project-local-lib</id>
  <url>file://${project.basedir}/../lib</url>
  <snapshots><enabled>true</enabled><checksumPolicy>ignore</checksumPolicy></snapshots>
</repository>
```

Docker build implication: the build context in `docker-compose.yml` is the
**repo root** (`context: .`), not `backend/`, so the Docker daemon can see
`lib/`. Don't change that without also publishing the connector.

When the connector is published to Maven Central / a remote repo, delete
`lib/` and the `<repository>` block. The `<dependency>` stays unchanged.

### JAXB pin (DataSonnet 2.5.2 quirk)

DataSonnet's Java format plugin loads `javax.xml.bind.*` in its static
initializer. Spring Boot 3's dependency management upgrades the connector's
transitive `jakarta.xml.bind-api` to Jakarta 4, which removes `javax.xml.bind`.
The POM pins the legacy `javax` JAXB 2.3.1 API + impl explicitly so
`com.datasonnet.spi.DataFormatService` initializes. Do not remove that pin
unless the connector is upgraded to a DataSonnet version that uses
`jakarta.xml.bind`.

### HttpClient 5

The connector declares `httpcomponents.client5:httpclient5` as `provided`, so
the backend POM supplies it at runtime. Keep it.

## Run, build, package

```bash
cd backend

# Run from source — auto-deploys the BPMN on startup
mvn spring-boot:run

# Package (single jar)
mvn package
java -jar target/cib7-react-poc-backend-0.1.0.jar

# Docker — but build from the repo root so lib/ is in the context
docker build -f backend/Dockerfile -t cib7-poc-backend .
# …or just:  docker compose up --build
```

The engine listens on `http://localhost:8080/engine-rest`. The CIB seven web
apps (Cockpit / Tasklist / Admin) are **not** included — to add them, add the
`cibseven-bpm-spring-boot-starter-webapp` dependency.

## Conventions

- **Style.** Google Java Style Guide; match the existing two classes for
  Javadoc tone and import ordering.
- **Package.** Everything under `com.poc.cib7`. Add sub-packages when there's
  a real reason (e.g. `com.poc.cib7.delegate` once delegate beans appear).
- **Configuration over Java code.** Prefer `application.yaml` for engine
  config; reach for a `@Bean` only when YAML cannot express it (the Connect
  plugin is the canonical example).
- **No comments restating what code does.** The existing classes use Javadoc
  on the class level to explain *why* the class exists; keep that pattern.
