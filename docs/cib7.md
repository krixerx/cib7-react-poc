# CIB seven engine module (`cib7/`)

**When to read this:** before editing anything under `cib7/`; when changing
the BPMN, the engine config, the connector wiring, or `pom.xml`; when
investigating a startup failure or a service-task execution issue.

This module is the embedded CIB seven 2.1 process engine + REST API. The
directory is named `cib7/` (not `cib7/`) so the name is free for a real
cib7/BFF module if and when one is added.

**Contents**
1. [Stack](#stack)
2. [File layout](#file-layout)
3. [Spring Boot wiring](#spring-boot-wiring)
4. [Engine configuration (`application.yaml`)](#engine-configuration-applicationyaml)
5. [BPMN files](#bpmn-files)
6. [Connect plugin and connector](#connect-plugin-and-connector)
7. [Authentication and authorization](#authentication-and-authorization)
8. [Maven and JDK](#maven-and-jdk)
9. [Run, build, package](#run-build-package)
10. [Conventions](#conventions)

---

## Stack

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5 |
| Process engine | CIB seven 2.1 (Camunda 7 fork) via `cibseven-bpm-spring-boot-starter-rest` |
| Database | H2, in-memory (no `spring.datasource` configured) |
| Connectors | `cibseven-engine-plugin-connect` + `cibseven-connect-http-client` (official `http-connector`) |
| Build | Maven, `spring-boot-maven-plugin` |

## File layout

```
cib7/
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

**Location.** `cib7/src/main/resources/processes/`. Anything matching
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

**Task gating — `candidateGroups`.** Both user tasks in
`person-registration.bpmn` carry
`camunda:candidateGroups="/task-executor"`. Engine authorization is on
(`camunda.bpm.authorization.enabled: true`), so the engine refuses
`claim`/`complete` from any authenticated user who is not a member of the
Keycloak group `/task-executor`. The leading slash matches the plugin's
`useGroupPathAsCamundaGroupId: true` setting — Camunda's group id is the
Keycloak group's path, not its name or UUID. See
[Authentication and authorization](#authentication-and-authorization).

**Variables.** Plain typed variables (`firstName: String`, `objectId: String`,
`price: Double`, `decision: String`, …), not a single `json` Spin variable.
The variable name + type form part of the form contract.

**Existing process — `person-registration.bpmn`.**

```
StartEvent_1
  → Task_SubmitDetails   userTask    formKey="react:personal-details"
  → Task_GetPrice        serviceTask asyncBefore=true, connector="http-connector"
  → Task_Review          userTask    formKey="react:review-application"
  → Gateway_Decision     exclusiveGateway, branches on ${decision == "approve"}
  → EndEvent_Approved | EndEvent_Rejected
```

Process variables and which step writes each: see the header comment inside
the BPMN file.

## Connect plugin and connector

The Connect plugin (registered in `ConnectorConfiguration.java`) lets the
engine parse `<camunda:connector>` extension elements. The
[`cibseven-connect-http-client`](https://mvnrepository.com/artifact/org.cibseven.connect/cibseven-connect-http-client)
dependency in `cib7/pom.xml` brings the official `http-connector` — a Connect
SPI implementation wrapping Apache HttpClient 5 — onto the classpath, where
the Connect plugin auto-discovers it.

Input parameters: `method`, `url`, `headers` (a `<camunda:map>`), `payload`,
`contentType`. Output parameters: `statusCode`, `headers`, `response` (the raw
body as a String).

It is wired inline inside `Task_GetPrice`'s `<bpmn:extensionElements>`. The
response body is parsed with Spin (bundled with the CIB seven engine) to read
`data.price` out:

```xml
<camunda:connector>
  <camunda:connectorId>http-connector</camunda:connectorId>
  <camunda:inputOutput>
    <camunda:inputParameter name="url">https://api.restful-api.dev/objects/${objectId}</camunda:inputParameter>
    <camunda:inputParameter name="method">GET</camunda:inputParameter>
    <camunda:inputParameter name="headers">
      <camunda:map>
        <camunda:entry key="Accept">application/json</camunda:entry>
      </camunda:map>
    </camunda:inputParameter>
    <camunda:outputParameter name="price">${S(response).prop('data').prop('price').numberValue()}</camunda:outputParameter>
  </camunda:inputOutput>
</camunda:connector>
```

The `asyncBefore="true"` flag means the service task runs in the job executor:
after the previous user task is completed, the call happens on a background
thread; the next user task appears a moment later. The Tasks page **Refresh**
button is the polling mechanism.

## Authentication and authorization

End-to-end Keycloak: every `/engine-rest/*` request must carry a valid Bearer
JWT issued by Keycloak, and the engine enforces `candidateGroups` based on the
caller's Keycloak group membership.

### Pieces, by role

| Role | Implementation | Where |
|---|---|---|
| OIDC identity provider | Keycloak (realm `cib7-poc`) | `keycloak/realm-export.json`, compose service |
| Identity Provider Plugin | `org.cibseven.bpm.extension:cibseven-keycloak:2.1.0` | declared in `pom.xml`; activated by `KeycloakIdentityProvider.java` |
| JWT validation | `spring-boot-starter-oauth2-resource-server` | `RestApiSecurityConfig.java` |
| Audience pin | `AudienceValidator` (rejects tokens without `cib7-rest-api` in `aud`) | `AudienceValidator.java` |
| Engine identity binding | `KeycloakAuthenticationFilter` (writes `IdentityService.setAuthentication` per request) | `KeycloakAuthenticationFilter.java` |
| Engine authorization | `camunda.bpm.authorization.enabled: true` | `application.yaml` |
| BPMN gating | `camunda:candidateGroups="/task-executor"` on each user task | `person-registration.bpmn` |

The five Java files under `com/poc/cib7/keycloak/` are **verbatim copies of
the plugin's reference example** (`examples/sso-kubernetes/.../rest/` and
`.../plugin/` packages, repackaged). They are not custom logic — they are the
plugin author's published recipe for wiring Spring Security to the engine's
`IdentityService`. Keep them in sync with the upstream plugin when bumping
its version.

### Configuration surface (`application.yaml`)

| Block | Purpose |
|---|---|
| `app.keycloak.{issuer-uri,jwk-set-uri,user-name-attribute}` | Read by `RestApiSecurityConfig`. Custom prefix (not `spring.security.oauth2.client.*`) so Spring Boot's auto-config doesn't try OIDC discovery against an URL the engine container can't reach. |
| `rest.security.{enabled,provider,required-audience}` | Activates the filter chain and the audience claim check |
| `plugin.identity.keycloak.*` | All Keycloak Admin REST API config — issuer URL, admin URL, client credentials, `useUsernameAsCamundaUserId`, `useGroupPathAsCamundaGroupId` |
| `camunda.bpm.authorization.enabled: true` | Required for candidateGroups to be enforced |
| `camunda.bpm.admin-user.id: homer` | Bootstraps admin authorizations on the seeded user so the engine doesn't 403 the very first call |

All Keycloak URLs are env-driven (`KEYCLOAK_URL`, `KEYCLOAK_ISSUER_URL`,
`KEYCLOAK_REALM`, `KEYCLOAK_BACKEND_CLIENT_ID`,
`KEYCLOAK_BACKEND_CLIENT_SECRET`, `KEYCLOAK_REST_AUDIENCE`) with localhost
defaults that work for `mvn spring-boot:run` against a
`docker compose up keycloak`. Inside Docker, `KEYCLOAK_URL` is the internal
docker-network URL (`http://keycloak:8080`) while `KEYCLOAK_ISSUER_URL` is
the public URL the browser uses (`http://localhost:8180`); see
[architecture.md § Deployment topology](architecture.md#deployment-topology)
for the rationale.

### Mental model: who's calling?

The engine has three concepts of "who" for a given REST call:

1. **HTTP-layer principal** — `JwtAuthenticationToken` populated by
   spring-security after JWT validation. Identifies the bearer of the token.
2. **Engine identity** — `IdentityService.setAuthentication(userId, groups)`
   set by `KeycloakAuthenticationFilter` before the handler runs and cleared
   after. This is what `taskService.complete()` checks against candidate
   groups, what `task.assignee = "${currentUser()}"` resolves to, and what
   ends up in history.
3. **Looked-up identity** — when the engine needs full user/group records
   (Cockpit listings, candidate-group membership), the plugin queries
   Keycloak's Admin REST API live and exposes the results through
   `IdentityService.createUserQuery()` / `createGroupQuery()`.

The chain only works end to end if **all three** are wired. Removing any one
of: the resource-server filter chain, the authentication filter, the identity
provider plugin, or `authorization.enabled`, breaks the model — usually
silently.

## Maven and JDK

### JDK 17

CIB seven 2.1 + Spring Boot 3.5 require **JDK 17**. The default `java` on the
developer's PATH may be JDK 11 — set `JAVA_HOME` to a JDK 17 before running
Maven. The Docker build uses an `eclipse-temurin:17` base image so it does not
depend on the host JDK.

### Dependencies

All dependencies are resolved from Maven Central. `cibseven-connect-http-client`
brings `httpclient5` transitively, so there is no explicit pin needed. There
is no project-local Maven repository — everything in `cib7/pom.xml` is a plain
`<dependency>`.

## Run, build, package

```bash
cd cib7

# Run from source — auto-deploys the BPMN on startup
mvn spring-boot:run

# Package (single jar)
mvn package
java -jar target/cib7-react-poc-cib7-0.1.0.jar

# Docker
docker build -t cib7-poc-cib7 cib7/
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
