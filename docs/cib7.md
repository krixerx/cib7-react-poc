# CIB seven engine module (`cib7/`)

**When to read this:** before editing anything under `cib7/`; when changing
the BPMN, the engine config, the connector wiring, or `pom.xml`; when
investigating a startup failure or a service-task execution issue.

This module is the embedded CIB seven 2.2 process engine + REST API — and
deliberately nothing else: engine, plugins, connectors, security wiring, and
the BPMN/DMN/FreeMarker resources. Every business REST endpoint (`/api/**` —
public confirmation/payment links, the vehicle registry, documents) lives in
the separate `backend/` microservice; the engine reaches it via the
http-connector like any other external REST service.

**Contents**
1. [Stack](#stack)
2. [File layout](#file-layout)
3. [Spring Boot wiring](#spring-boot-wiring)
4. [Engine configuration (`application.yaml`)](#engine-configuration-applicationyaml)
5. [BPMN files](#bpmn-files)
    - [Large process variables (bytes-typed)](#large-process-variables-bytes-typed)
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
| Process engine | CIB seven 2.2 (Camunda 7 fork) via `cibseven-bpm-spring-boot-starter-webapp` (includes REST + Cockpit/Tasklist/Admin) |
| Database | H2, in-memory (no `spring.datasource` configured) |
| Connectors | `cibseven-engine-plugin-connect` + `cibseven-connect-http-client` (official `http-connector`) |
| Template engine | `cibseven-template-engines-freemarker` — renders connector payloads from `templates/*.ftl` |
| DMN | Bundled with the engine; each service's `.dmn` files deploy in the same per-service deployment as its BPMN (`decisionRefBinding="deployment"`) |
| PDF generation | Out-of-process: [Gotenberg](https://gotenberg.dev/) (headless Chromium) fronted by a tiny [`pdf-renderer/`](../pdf-renderer/) Node sidecar that does JSON-in / JSON-out so the http-connector can call it like any other REST endpoint |
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
    │   ├── BusConfiguration.java          — exposes ${busBaseUrl} to BPMN JUEL (the integration bus)
    │   ├── FrontendConfiguration.java     — exposes ${frontendBaseUrl} (links in emails)
    │   ├── PdfHelper.java                 — @Component("pdf") — base64 ↔ byte[] bridge
    │   ├── AuthorizationBootstrap.java    — grants applicant + civil-servant engine perms on startup
    │   └── keycloak/                      — Spring Security + Keycloak identity-provider wiring
    │       └── webapp/                    — OAuth2 login + ContainerBasedAuthenticationProvider for Cockpit/Tasklist/Admin
    └── resources/
        ├── application.yaml               — engine + OAuth2 client config
        ├── processes/<service>/           — one engine deployment per folder (ServiceDeployments.java)
        │   ├── vehicle-registration/      — vehicle-registration.bpmn + vehicle-auto-approval.dmn
        │   ├── business-registration/     — business-registration.bpmn + business-auto-approval.dmn
        │   ├── transport-vehicle-registration/  — BPMN + eligibility/fee DMNs
        │   └── transport-learning-permit/       — BPMN + eligibility DMN
        └── templates/                     — FreeMarker payloads for the http-connector
            └── *.json.ftl                 — Mailpit emails, pdf-renderer renders,
                                             backend /api/documents calls
```

The `com.poc.cib7.keycloak` package contains five classes verbatim from the
`cibseven-keycloak` plugin's reference example (see
[§ Authentication and authorization](#authentication-and-authorization)).
Everything else is local: the application entry point, the connector plugin
bean, the JUEL config beans, and the group authorization bootstrap. When
adding new engine-side logic (delegate beans, listeners, engine plugins),
follow Spring Boot conventions (a `@Component` / `@Configuration` class under
`com.poc.cib7`). New `@RestController`s do **not** belong here — business
endpoints go in `backend/`.

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

### `BusConfiguration.java` / `FrontendConfiguration.java`

Each exposes a Spring `String` bean driven by an env var. The engine's
expression manager auto-binds beans by name, so the BPMN can reference them
without any further wiring:

| Bean | Env var | Used by BPMN as |
|---|---|---|
| `busBaseUrl` | `BUS_URL` | `${busBaseUrl}/...` for ALL outbound HTTP. The integration bus (`esb`, Apache Camel) routes each path: `/api/v1/send`→Mailpit, `/render`→pdf-renderer, `/api/public/**` and `/api/documents/**`→backend |
| `frontendBaseUrl` | `FRONTEND_BASE_URL` | links embedded in confirmation / payment emails |

The former `MailConfiguration` / `PdfConfiguration` / `BackendConfiguration`
beans (one per downstream system) were collapsed into the single `busBaseUrl`;
the per-system addresses now live in `esb/routes/*.yaml`. The internal
`X-Internal-Token` secret also moved to the bus — the engine no longer sets it
(the bus injects it on `/api/documents`), so `INTERNAL_TASK_TOKEN` is an `esb`
env var, not an engine one.

Defaults target `localhost` ports for `mvn spring-boot:run`; docker-compose
overrides them to docker-network aliases (`mailpit`, `pdf-renderer`,
`backend`).

### `PdfHelper.java`

`@Component("pdf")` with two methods: `decode(String)` → `byte[]` and
`encode(byte[])` → `String`. Bridges base64 ↔ raw bytes for the BPMN, where
JUEL/FreeMarker have no built-in base64 and we deliberately want the PDF
stored as a `byte[]` process variable. See
[§ Large process variables](#large-process-variables-bytes-typed) for the
rationale.

### `AuthorizationBootstrap.java`

Runs once after `ApplicationReadyEvent` and grants both the `applicant` and
the `civil-servant` engine groups the minimum permissions to do their jobs
across all deployed process definitions. With `camunda.bpm.authorization.enabled:
true`, a new group has no permissions by default — without this bootstrap
Bart's PartA calls return empty arrays and Homer's PartB worklist gets a 500
"no matching process definition" (the engine hides resources the caller can't
read rather than returning 403).

Grants summary:

| Group | Resource | Permissions |
|---|---|---|
| `applicant` | `PROCESS_DEFINITION *` | READ, CREATE_INSTANCE, READ_INSTANCE, READ_HISTORY, UPDATE_INSTANCE, READ_TASK, UPDATE_TASK |
| `applicant` | `PROCESS_INSTANCE *` | CREATE |
| `applicant` | `TASK *` | READ, UPDATE |
| `civil-servant` | `PROCESS_DEFINITION *` | READ, READ_INSTANCE, READ_HISTORY, UPDATE_INSTANCE, READ_TASK, UPDATE_TASK |
| `civil-servant` | `TASK *` | READ, UPDATE |

`civil-servant` does NOT get `CREATE_INSTANCE` or `CREATE` on `PROCESS_INSTANCE` —
civil servants don't start cases, applicants do. BPMN `candidateGroups` still
gate which tasks a civil servant can actually claim/complete on top of these
resource-level grants, so the wildcard resource id is safe.

Idempotent — re-running on a clean H2 startup is fine, and on a re-deploy
the same-group-same-resource check skips already-existing grants. The
`cib7-admin` group is handled separately by the `cibseven-keycloak`
plugin's `administratorGroupName: cib7-admin` config — no bootstrap needed,
it gets full admin powers automatically.

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
  auto-deployment-enabled: false
  database:
    schema-update: true
```

| Key | Why |
|---|---|
| `server.port: 8080` | Hard-coded so the SPA proxy targets are stable in both dev and Docker |
| `camunda.bpm.auto-deployment-enabled: false` | The starter's auto-deploy would bundle every BPMN/DMN into one `SpringAutoDeployment` — a single versioning/rollback unit for ALL services. `ServiceDeployments.java` replaces it (see below) |
| `camunda.bpm.database.schema-update: true` | Lets the engine create its tables on first start (required for the in-memory H2 lifecycle) |
| *(no `spring.datasource`)* | Triggers Spring Boot's H2 auto-config — in-memory DB, state wiped on restart |

## Per-service deployments (`ServiceDeployments.java`)

`com.poc.cib7.ServiceDeployments` scans `classpath*:processes/*/` at
startup and creates **one named engine deployment per service folder**
(deployment name = folder name = the spec folder name under
`docs/business/services/`), with `enableDuplicateFiltering(true)` so a
re-deploy of an unchanged service is a no-op and an edit re-versions only
that service. Benefits over the single-bundle starter deploy:

- independent versioning per service (no cross-service version bumps),
- independent rollback/delete in Cockpit (deployment delete cascades per
  service, not across the whole catalog),
- `camunda:decisionRefBinding="deployment"` on business rule tasks now
  resolves each process's DMNs from the same service deployment — an
  in-flight process version never silently picks up a newer decision
  table.

It runs in `@PostConstruct`, before the HTTP port opens, so
`/engine-rest` never serves a window with missing definitions.

## BPMN files

**Location.** `cib7/src/main/resources/processes/<service>/` — one folder
per service; the folder name becomes the engine deployment name. A
service's DMN files live in the same folder (required by the
`deployment` decision binding).

**Repo convention: one file per process** (one top-level `<bpmn:process>`).
The engine does not enforce this — a single file can technically hold
multiple processes — but we keep it one-to-one so the file name matches
the process and diffs stay small.

**Editing.** Use the Camunda Modeler (it understands the `camunda:` namespace
and DI). Hand-editing the XML is fine for small changes; keep the
`<bpmndi:BPMNDiagram>` in sync so the diagram still opens.

**Namespace.** Use the `camunda:` namespace
(`xmlns:camunda="http://camunda.org/schema/1.0/bpmn"`), not `cib:` — CIB seven
2.2 uses `camunda:` exactly like Camunda 7. The spec's §5.3 is inaccurate on
this point; see the deviations table in the top-level
[`README.md`](../README.md#deviations-from-the-spec).

**User-task → React-form contract.** A user task is bound to a React form by
`camunda:formKey="react:<form-id>"`. The form id (everything after `react:`)
must match a key in `frontend/src/forms/registry.ts`. There is no publish-time
manifest validation — if the key is wrong, the TaskDetail page renders an
error. See [`frontend.md` — Forms](frontend.md#forms).

**Task gating — `assignee` / `candidateGroups`.** The applicant task in
each BPMN carries `camunda:assignee="${initiator}"` so it
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

**Reference process — `vehicle-registration.bpmn`** (the OÜ flow in
`business-registration.bpmn` has the same shape with founder semantics):

```
StartEvent_1 (camunda:initiator="initiator")
  → Task_SubmitDetails            userTask         formKey="react:owner-vehicle"
                                                    camunda:assignee="${initiator}"
                                                    (also re-entered on send-back)
  → Task_AttachIdDocument         serviceTask      http-connector → backend /api/documents/move-pending
  → SubProcess_OwnerConfirmations multi-instance   one branch per co-owner: signing email +
                                                    ReceiveTask_OwnerConfirmation (message
                                                    correlation from the public confirm page)
  → Task_WaitSendToProcess        receiveTask      "owner submits to process" message
  → Task_GetPrice                 serviceTask      asyncBefore, http-connector →
                                                    backend /api/public/vehicle-registry
  → Task_AutoDecide               businessRuleTask decisionRef="vehicle-auto-approval"
                                                    → autoDecision (singleEntry)
  → Gateway_AutoApproval          exclusiveGateway
       autoDecision == "approve"  → (skips review)
       default                    → Task_Review
  → Task_Review                   userTask         formKey="react:vehicle-review"
                                                    camunda:candidateGroups="civil-servant"
       ┊ boundary timer R/PT2M (non-interrupting)
       ▼ Task_SendReminderEmail (http-connector → Mailpit) → EndEvent_ReminderSent
  → Gateway_Decision              exclusiveGateway
       decision == "approve"  → invoice + payment path below
       default (sendback)     → Task_SendBackEmail → Task_SubmitDetails (loop)
  → Task_GeneratePdf → Task_StoreApprovalPdf       fee invoice (pdf-renderer →
                                                    backend /api/documents/server-upload)
  → Task_SendApprovalEmail                          invoice email with /pay link
  → Task_WaitForPayment           receiveTask      "PaymentReceived" message —
                                                    correlated by the backend when the
                                                    public /pay/{piId} page confirms
  → Task_GenerateCertificatePdf → Task_StoreCertificatePdf
  → EndEvent_Approved  "Vehicle registered"
```

Key process variables:
- `initiator` — login of the applicant who started the case (written by the
  start event so the applicant task can be reassigned on every loop).
- `firstName`, `lastName`, `age`, `objectId` (VIN), `applicantEmail`,
  `applicantToken`, `additionalOwners` (Json), `pendingIdDocument` (Json) —
  written by the applicant task.
- `price`, `vehicleAgeYears`, `vehicleMake/Model/Year/FuelType` — written by
  `Task_GetPrice` via Spin expressions on the `http-connector` response.
- `ownerConfirmations` (Json), `rejectedByOwner`, `sentToProcess` — written
  by the backend's public owner-confirmation endpoints via `/engine-rest`.
- `autoDecision` — written by `Task_AutoDecide`, mapped from the
  `vehicle-auto-approval` DMN's single output (`"approve"` or `"review"`).
- `decision` — written by the review task (`"approve"` or `"sendback"`).
- `sendBackReason` — set by the review form when sending back; cleared by
  the applicant form on resubmit so the next review cycle starts clean.
- `paymentReceived` — set by the backend's payment confirmation via
  message correlation.

**DMN — `vehicle-auto-approval.dmn`.** Hit policy `FIRST`. Inputs `age`,
`price`, and `vehicleAgeYears`; one output (`autoDecision`). (The OÜ flow's
`business-auto-approval.dmn` adds `applicantResidency`.) Mapped into the
process via
`camunda:resultVariable="autoDecision"` + `camunda:mapDecisionResult="singleEntry"`
on the business rule task — `singleEntry` extracts the single value of the
single-row result; if no rule matches you get `null`, which falls through to
the gateway's default branch and routes to the human reviewer.

### Large process variables (bytes-typed)

CIB seven on H2 stores String/Text-typed variables inline in
`ACT_RU_VARIABLE.TEXT_` and `ACT_HI_VARINST.TEXT_`, both
`VARCHAR(4000)`. Any String above 4000 chars throws
`JdbcBatchUpdateException: Value too long for column "TEXT_"` during the
history flush. Only `bytes` / `file` / `object` typed variables spill to
`ACT_GE_BYTEARRAY`, which is uncapped.

This is why `Task_GeneratePdf` decodes the base64 response into a `byte[]`
before storing it:

```xml
<camunda:outputParameter name="approvalPdfBytes">${pdf.decode(S(response).prop('base64').stringValue())}</camunda:outputParameter>
```

A `byte[]` returned from JUEL is auto-typed as `BytesValue` and the engine
writes the payload to `ACT_GE_BYTEARRAY` with only a reference in
`BYTEARRAY_ID_`. `approval-email.json.ftl` re-encodes to base64 at email
time with `${pdf.encode(approvalPdfBytes)}`.

Apply the same pattern to any payload above ~3 kB — large JSON snapshots,
signed documents, OCR scans, etc.

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

It is wired inline inside every integration service task across the two
BPMNs: registry lookup (`Task_GetPrice`), document promotion and storage
(`Task_AttachIdDocument` / `Task_AttachAoaDocument` → backend
`/api/documents/move-pending`; the `Task_Store*Pdf` tasks → backend
`/api/documents/server-upload`), email sends (Mailpit), and PDF renders
(pdf-renderer). For `Task_GetPrice` the response body is parsed with Spin
(bundled with the CIB seven engine), with per-property fallbacks so a
malformed response degrades to "review" instead of crashing the activity:

```xml
<camunda:connector>
  <camunda:connectorId>http-connector</camunda:connectorId>
  <camunda:inputOutput>
    <camunda:inputParameter name="url">${busBaseUrl}/api/public/vehicle-registry/vehicles/${objectId}</camunda:inputParameter>
    <camunda:inputParameter name="method">GET</camunda:inputParameter>
    <camunda:inputParameter name="headers">
      <camunda:map>
        <camunda:entry key="Accept">application/json</camunda:entry>
      </camunda:map>
    </camunda:inputParameter>
    <camunda:outputParameter name="price">${!S(response).hasProp('value') ? 9999 : S(response).prop('value').numberValue()}</camunda:outputParameter>
    <!-- + vehicleMake / vehicleModel / vehicleYear / vehicleFuelType / vehicleAgeYears -->
  </camunda:inputOutput>
</camunda:connector>
```

The `asyncBefore="true"` flag means the service task runs in the job executor:
after the previous user task is completed, the call happens on a background
thread; the next user task appears a moment later. The Tasks page **Refresh**
button is the polling mechanism.

The two email service tasks reuse the same connector. The target URL is built
from a Spring bean `busBaseUrl` registered by `BusConfiguration.java`; in JUEL
`${busBaseUrl}` resolves to whatever the `BUS_URL` env var is set to
(`http://esb:8080` inside docker-compose). The engine POSTs to the integration
bus, which routes `/api/v1/send` to Mailpit.
Mailpit's HTTP API is intentionally Mailpit-flavoured — `From`/`To` lists,
`Subject`, `Text` — there is no SMTP traffic involved.

`Task_GeneratePdf` follows the same pattern, posting to the bus
(`${busBaseUrl}/render`), which routes to the [`pdf-renderer/`](../pdf-renderer/)
sidecar.
pdf-renderer is a 20-line Express app that hides two warts Gotenberg
exposes: it accepts JSON (Gotenberg requires multipart/form-data) and
returns base64 inside JSON (Gotenberg returns raw binary, which the
http-connector mangles when it surfaces the response as a Java `String`).
The BPMN call site therefore looks identical to the email tasks — HTTP +
FreeMarker payload + Spin to read scalars off the response — instead of
needing a custom Java delegate. See the `pdf-renderer/server.js` source
for the multipart adapter.

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
| Group → narrow grants | `AuthorizationBootstrap` adds READ / CREATE_INSTANCE / READ_INSTANCE / READ_HISTORY / UPDATE_INSTANCE / READ_TASK / UPDATE_TASK for the `applicant` group (and a read/update set for `civil-servant`) on `ProcessDefinition:*`, plus CREATE on `ProcessInstance:*` and READ / UPDATE on `Task:*` | `AuthorizationBootstrap.java` |
| BPMN gating | `camunda:assignee="${initiator}"` on the applicant task, `camunda:candidateGroups="civil-servant"` on the review task | both BPMNs |

The five Java files under `com/poc/cib7/keycloak/` are **verbatim copies of
the plugin's reference example** (`examples/sso-kubernetes/.../rest/` and
`.../plugin/` packages, repackaged). They are not custom logic — they are the
plugin author's published recipe for wiring Spring Security to the engine's
`IdentityService`. Keep them in sync with the upstream plugin when bumping
its version.

`AuthorizationBootstrap.java` is the one piece of custom auth code, and it
is intentionally narrow: it only adds grants for the `applicant` and
`civil-servant` groups (wildcard resource ids so new services need no Java
change). Admin (`cib7-admin`) is covered by the plugin's
`administratorGroupName` config — which also makes the backend's
`service-account-cib7-business` an engine admin, since the realm export puts
that service account in `/cib7-admin`. Per-task access is still gated by the
BPMN's `assignee` / `candidateGroups`.

### Configuration surface (`application.yaml`)

| Block | Purpose |
|---|---|
| `app.keycloak.{issuer-uri,jwk-set-uri,user-name-attribute}` | Read by `RestApiSecurityConfig`. Custom prefix (not `spring.security.oauth2.client.*`) so Spring Boot's auto-config doesn't try OIDC discovery against an URL the engine container can't reach. |
| `rest.security.{enabled,provider,required-audience}` | Activates the filter chain and the audience claim check |
| `plugin.identity.keycloak.*` | All Keycloak Admin REST API config — issuer URL, admin URL, client credentials, `useUsernameAsCamundaUserId`, `useGroupPathAsCamundaGroupId` |
| `camunda.bpm.authorization.enabled: true` | Required for candidateGroups to be enforced |
| `camunda.bpm.admin-user.id: admin` | Bootstraps admin authorizations on the dedicated `admin` user (in `/cib7-admin`) so the engine doesn't 403 the very first call. Was `homer` before — split out so the admin role is separate from the civil-servant role. |
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

### JDK 21

CIB seven + Spring Boot 3.5 build on **JDK 21** (the minimum is 17, but the
project targets 21 to match CIB seven's own published Docker images). The
default `java` on the developer's PATH may be JDK 11 — set `JAVA_HOME` to a
JDK 21 before running Maven. The Docker build uses an `eclipse-temurin:21` base
image so it does not depend on the host JDK.

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
`http://localhost:8080/camunda` when run standalone (`mvn spring-boot:run`
or the bare jar). Under `docker compose up` the engine container no longer
publishes 8080 to the host — Traefik fronts it on `:3000`, so the same
URLs become `http://localhost:3000/engine-rest` and
`http://localhost:3000/camunda`. `ForwardedHeaderFilter` in
`WebappSecurityConfig` honors X-Forwarded-* so the OAuth2 redirect URI
the engine generates uses the public host. The webapp starter
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
`http://localhost:3000/login/oauth2/code/keycloak` redirect URI —
behind the Traefik ingress, same origin as the SPA).

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
