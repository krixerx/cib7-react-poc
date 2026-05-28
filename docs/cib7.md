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
| Process engine | CIB seven 2.1 (Camunda 7 fork) via `cibseven-bpm-spring-boot-starter-webapp` (includes REST + Cockpit/Tasklist/Admin) |
| Database | H2, in-memory (no `spring.datasource` configured) |
| Connectors | `cibseven-engine-plugin-connect` + `cibseven-connect-http-client` (official `http-connector`) |
| DMN | Bundled with the engine; `auto-approval.dmn` auto-deployed alongside BPMN |
| Webapp SSO | `spring-boot-starter-oauth2-client` + `ContainerBasedAuthenticationFilter` (cibseven-keycloak plugin recipe) |
| Build | Maven, `spring-boot-maven-plugin` |

## File layout

```
cib7/
├── pom.xml
├── Dockerfile
└── src/main/
    ├── java/com/poc/cib7/
    │   ├── Cib7PocApplication.java        — @SpringBootApplication entry point
    │   ├── ConnectorConfiguration.java    — registers ConnectProcessEnginePlugin + Spin plugin
    │   ├── MailConfiguration.java         — exposes ${mailApiBaseUrl} to BPMN JUEL
    │   ├── AuthorizationBootstrap.java    — grants the applicant group engine perms on startup
    │   └── keycloak/                      — Spring Security + Keycloak identity-provider wiring
    │       └── webapp/                    — OAuth2 login + ContainerBasedAuthenticationProvider for Cockpit/Tasklist/Admin
    └── resources/
        ├── application.yaml               — engine + auto-deploy + OAuth2 client config
        └── processes/
            ├── person-registration.bpmn   — auto-deployed on startup
            └── auto-approval.dmn          — auto-deployed alongside BPMN
```

The `com.poc.cib7.keycloak` package contains five classes verbatim from the
`cibseven-keycloak` plugin's reference example (see
[§ Authentication and authorization](#authentication-and-authorization)).
Everything else is local: the application entry point, the connector plugin
bean, and the applicant-group authorization bootstrap. When adding new
logic (delegate beans, listeners, custom REST controllers), follow Spring
Boot conventions (a `@Component` / `@Configuration` class under
`com.poc.cib7`).

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

### `AuthorizationBootstrap.java`

Runs once after `ApplicationReadyEvent` and grants the `applicant` engine
group the minimum permissions to start, read, and complete its own
`personRegistration` instances. With `camunda.bpm.authorization.enabled:
true`, a new group has no permissions by default — without this bootstrap
Bart's `/engine-rest` calls return empty arrays (filtered by the engine's
authorization layer) and the Services page looks blank.

Idempotent — re-running on a clean H2 startup is fine, and on a re-deploy
the same-group-same-resource check skips already-existing grants. The
`cib7-admin` group is handled separately by the `cibseven-keycloak`
plugin's `administratorGroupName: cib7-admin` config — no bootstrap needed.

> **Gotcha** — the cibseven-keycloak plugin exposes engine group IDs
> **without** the leading slash of the Keycloak group path, even when
> `useGroupPathAsCamundaGroupId: true` is set (the path `/applicant`
> becomes the engine group id `applicant`). The bootstrap and every
> BPMN `candidateGroups` attribute must use this slash-less form to
> match; the realm export still uses the canonical paths
> (`/applicant`, `/civil-servant`, `/cib7-admin`).

## Engine configuration (`application.yaml`)

```yaml
server:
  port: 8080

camunda.bpm:
  deployment-resource-pattern:
    - classpath*:**/*.bpmn
    - classpath*:**/*.dmn
  database:
    schema-update: true
```

| Key | Why |
|---|---|
| `server.port: 8080` | Hard-coded so the SPA proxy targets are stable in both dev and Docker |
| `camunda.bpm.deployment-resource-pattern` | List of glob patterns. Auto-deploys every `*.bpmn` **and** `*.dmn` on the classpath at startup — no Java code needed |
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

**Task gating — `assignee` / `candidateGroups`.** The applicant task in
`person-registration.bpmn` carries `camunda:assignee="${initiator}"` so it
goes only to the user who started the case; the review task carries
`camunda:candidateGroups="civil-servant"` so it goes to the back-office
group. Engine authorization is on (`camunda.bpm.authorization.enabled:
true`), so the engine refuses `claim`/`complete` from any other user.

**No leading slash on engine group ids.** Although the Keycloak group
*path* is `/civil-servant`, the cibseven-keycloak plugin maps that to the
engine group id `civil-servant` (slash stripped) even with
`useGroupPathAsCamundaGroupId: true`. `candidateGroups` and any
authorization grant for this group must use the slash-less form to match
what `IdentityService.createGroupQuery().groupMember(user).list()`
actually returns. The Keycloak realm export keeps the canonical
paths (`/applicant`, `/civil-servant`, `/cib7-admin`).

See [Authentication and authorization](#authentication-and-authorization).

**Variables.** Plain typed variables (`firstName: String`, `objectId: String`,
`price: Double`, `decision: String`, …), not a single `json` Spin variable.
The variable name + type form part of the form contract.

**Existing process — `person-registration.bpmn`.**

```
StartEvent_1 (camunda:initiator="initiator")
  → Task_SubmitDetails       userTask          formKey="react:personal-details"
                                                camunda:assignee="${initiator}"
                                                (also re-entered on send-back)
  → Task_GetPrice            serviceTask       asyncBefore=true, connector="http-connector"
  → Task_AutoDecide          businessRuleTask  decisionRef="auto-approval"
                                                → autoDecision (singleEntry)
  → Gateway_AutoApproval     exclusiveGateway
       autoDecision == "approve"  → EndEvent_Approved (auto-approved, skips PartB)
       default                     → Task_Review
  → Task_Review              userTask          formKey="react:review-application"
                                                camunda:candidateGroups="civil-servant"
       ┊
       ┊ boundary timer R/PT2M (non-interrupting)
       ▼
       Task_SendReminderEmail (http-connector → Mailpit) → EndEvent_ReminderSent
  → Gateway_Decision         exclusiveGateway
       decision == "approve"  → EndEvent_Approved
       default (sendback)     → Task_SendBackEmail (http-connector → Mailpit)
                              → Task_SubmitDetails  (loops back to applicant)
```

Process variables:
- `initiator` — login of the applicant who started the case (written by the
  start event so the applicant task can be reassigned on every loop).
- `firstName`, `lastName`, `age`, `objectId` — written by the applicant task.
- `price` — written by `Task_GetPrice` via the Spin expression on the
  `http-connector`'s response.
- `autoDecision` — written by `Task_AutoDecide`, mapped from the `auto-approval`
  DMN's single output. Values: `"approve"` or `"review"`.
- `decision` — written by the review task (`"approve"` or `"sendback"`).
- `sendBackReason` — set by the review form when sending back; cleared by
  the applicant form on resubmit so the next review cycle starts clean.

**DMN — `auto-approval.dmn`.** Hit policy `FIRST`. Two inputs (`age`,
`price`), one output (`autoDecision`). Mapped into the process via
`camunda:resultVariable="autoDecision"` + `camunda:mapDecisionResult="singleEntry"`
on the business rule task — `singleEntry` extracts the single value of the
single-row result; if no rule matches you get `null`, which falls through to
the gateway's default branch and routes to the human reviewer.

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

It is wired inline inside three service tasks: `Task_GetPrice` (catalogue
lookup), `Task_SendReminderEmail` (timer-driven email), and
`Task_SendBackEmail` (sent on the rejection path). For `Task_GetPrice` the
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

The two email service tasks reuse the same connector against Mailpit. The
target URL is built from a Spring bean `mailApiBaseUrl` registered by
`MailConfiguration.java`; in JUEL `${mailApiBaseUrl}` resolves to whatever
the `MAIL_API_URL` env var is set to (defaults to `http://localhost:8025`
for `mvn spring-boot:run`; `http://mailpit:8025` inside docker-compose).
Mailpit's HTTP API is intentionally Mailpit-flavoured — `From`/`To` lists,
`Subject`, `Text` — there is no SMTP traffic involved.

The non-interrupting timer boundary event on `Task_Review`
(`cancelActivity="false"`, `R/PT2M`) is what schedules the reminder branch.
"Non-interrupting" means the user task stays open and the timer can fire
again on its next cycle. The job executor must be running for the timer to
fire — it is, by default, in the Spring Boot starter.

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
| Admin group → engine admin | `administratorGroupName: cib7-admin` on the identity plugin | `application.yaml` |
| Applicant group → narrow grants | `AuthorizationBootstrap` adds READ / CREATE_INSTANCE / READ_INSTANCE / READ_HISTORY / UPDATE_INSTANCE / READ_TASK / UPDATE_TASK for the `applicant` group on the `personRegistration` definition, plus CREATE on `ProcessInstance:*` and READ / UPDATE on `Task:*` | `AuthorizationBootstrap.java` |
| BPMN gating | `camunda:assignee="${initiator}"` on the applicant task, `camunda:candidateGroups="civil-servant"` on the review task | `person-registration.bpmn` |

The five Java files under `com/poc/cib7/keycloak/` are **verbatim copies of
the plugin's reference example** (`examples/sso-kubernetes/.../rest/` and
`.../plugin/` packages, repackaged). They are not custom logic — they are the
plugin author's published recipe for wiring Spring Security to the engine's
`IdentityService`. Keep them in sync with the upstream plugin when bumping
its version.

`AuthorizationBootstrap.java` is the one piece of custom auth code, and it
is intentionally narrow: it only adds grants for `applicant`. Admin
(`cib7-admin`) is covered by the plugin's `administratorGroupName` config,
and civil-servant access is gated by the BPMN's `candidateGroups`.

### Configuration surface (`application.yaml`)

| Block | Purpose |
|---|---|
| `app.keycloak.{issuer-uri,jwk-set-uri,user-name-attribute}` | Read by `RestApiSecurityConfig`. Custom prefix (not `spring.security.oauth2.client.*`) so Spring Boot's auto-config doesn't try OIDC discovery against an URL the engine container can't reach. |
| `rest.security.{enabled,provider,required-audience}` | Activates the filter chain and the audience claim check |
| `plugin.identity.keycloak.*` | All Keycloak Admin REST API config — issuer URL, admin URL, client credentials, `useUsernameAsCamundaUserId`, `useGroupPathAsCamundaGroupId` |
| `camunda.bpm.authorization.enabled: true` | Required for candidateGroups to be enforced |
| `camunda.bpm.admin-user.id: homer` | Bootstraps admin authorizations on the seeded user so the engine doesn't 403 the very first call |
| `plugin.identity.keycloak.administratorGroupName: cib7-admin` | Grants engine admin authorizations to everyone in the `/cib7-admin` Keycloak group on every startup |

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

The engine listens on `http://localhost:8080/engine-rest` and the
CIB seven webapps (Cockpit / Tasklist / Admin) on
`http://localhost:8080/camunda`. The webapp starter
(`cibseven-bpm-spring-boot-starter-webapp`) transitively brings the REST
starter.

### Webapp SSO

Webapp auth lives in `com.poc.cib7.keycloak.webapp` and is the
cibseven-keycloak plugin's `sso-kubernetes` example, repackaged:

| Class | Role |
|---|---|
| `WebappSecurityConfig` | Second `SecurityFilterChain` (`@Order(2)`) matching `/camunda/**` + OAuth2 paths. Runs `.oauth2Login(...)`. Registers `ContainerBasedAuthenticationFilter` at order 201 to bridge the OAuth2 principal into `IdentityService`. |
| `KeycloakAuthenticationProvider` | Reads the `OidcUser`, looks up groups via the cibseven-keycloak plugin's read-only `IdentityService`, returns the user-id + groups to the engine. |
| `KeycloakLogoutHandler` | Redirects to Keycloak's OIDC logout endpoint with `id_token_hint` so the SSO session ends too. |

The Keycloak side is the `cib7-webapps` client in
`keycloak/realm-export.json` (confidential, standard flow,
`http://localhost:8080/login/oauth2/code/keycloak` redirect URI).

The OAuth2 client config in `application.yaml` deliberately **omits**
`spring.security.oauth2.client.provider.keycloak.issuer-uri` and lists every
endpoint explicitly — the same internal-vs-browser URL split as for JWKS in
`RestApiSecurityConfig`. Setting `issuer-uri` would trigger OIDC discovery
against the browser-visible URL at startup, which the engine container
can't reach inside docker-compose.

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
