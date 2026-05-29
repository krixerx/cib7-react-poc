# CIB seven 2.1 + React — Human Tasks POC

A proof of concept: a [CIB seven](https://cibseven.org) 2.1 process engine runs
a BPMN process with **two human tasks**, a **DMN auto-approval decision**, a
**non-interrupting timer boundary event**, and **five connector-backed
service tasks** (one fetches a product price, one renders an approval PDF
via [Gotenberg](https://gotenberg.dev/), three POST email notifications —
one with the PDF attached — to [Mailpit](https://mailpit.axllent.org/)). A
**React** app opens each human task with its own hand-written form; the
**Cockpit / Tasklist / Admin** webapps are also bundled with full Keycloak
SSO.

It is a slice of the larger design in
[`docs/human-role-react-forms-spec.md`](docs/human-role-react-forms-spec.md) —
see [Deviations from the spec](#deviations-from-the-spec) below.

## Test logins & consoles

| Console | URL | Login | Password |
|---|---|---|---|
| **React SPA** (PartA & PartB) | <http://localhost:3000> | `bart` / `homer` | same as username |
| &nbsp;&nbsp;↳ PartA — applicant | | `bart` | `bart` |
| &nbsp;&nbsp;↳ PartB — civil servant | | `homer` | `homer` |
| **CIB seven Admin** (users, groups, authorizations) | <http://localhost:8080/camunda/app/admin/> | `homer` | `homer` |
| **CIB seven Cockpit** (process instances, incidents) | <http://localhost:8080/camunda/app/cockpit/> | `homer` | `homer` |
| **CIB seven Tasklist** (legacy task UI) | <http://localhost:8080/camunda/app/tasklist/> | `homer` | `homer` |
| **CIB seven REST API** | <http://localhost:8080/engine-rest> | Bearer JWT from Keycloak | — |
| **Mailpit inbox** (process-sent emails) | <http://localhost:8025> | — | — |
| **Keycloak admin console** (realm / users / clients) | <http://localhost:8180/admin/> | `admin` | `admin` |

Role notes:

- **`bart`** (Bart Simpson) — `/applicant` group, sees PartA on the SPA.
- **`homer`** (Homer Simpson) — `/civil-servant` + `/cib7-admin` groups, sees
  PartB on the SPA and admin everything on the `/camunda` webapps. The
  `/cib7-admin` group grants engine admin authorizations; the applicant
  group's narrower authorizations are bootstrapped on startup by
  `cib7/src/main/java/com/poc/cib7/AuthorizationBootstrap.java`.
- Both webapp and SPA logins go through **Keycloak SSO** against the
  `cib7-poc` realm; the underlying user store is
  [`keycloak/realm-export.json`](keycloak/realm-export.json).

The SPA picks the role-appropriate UI from the JWT's realm roles:

- **PartA — applicant:** Services + My processes. Bart starts a process, fills
  the applicant form, and watches the status. If a civil servant sends the
  case back, the row's status shows "Sent back for corrections" and Bart can
  reopen the form (with the send-back reason shown as a banner) and resubmit.
- **PartB — back office:** Tasks + Incidents. Homer reviews the submitted
  application, then **Accept** (process ends approved) or **Send back…**
  (writes a reason variable and loops back to the applicant task).

Full realm in `keycloak/realm-export.json`.

---

## What it does

```
Person Registration (BPMN + DMN)

  start (initiator = applicant)
    │
    ▼  Submit personal details   user task   (applicant — PartA)
    │    first / last name, age, and a product picked from api.restful-api.dev
    │    assignee = ${initiator}
    │  ◀──────────────────────────────────────────────────────────────────┐
    ▼  Get price                 service task (http-connector)            │
    │    GET api.restful-api.dev/objects/{id} → data.price → price        │
    │                                                                     │
    ▼  Auto approval?            business rule task (DMN)                 │
    │    age + price → autoDecision ("approve" / "review")                │
    │                                                                     │
    ▼  Auto-approve? ── exclusive gateway ───▶ end approved   (skips PartB)
    │      else                                                           │
    ▼  Review application        user task   (civil servant — PartB)      │
    │    ⏱  PT2M non-interrupting timer ─▶  Send reminder email (Mailpit) │
    │    Accept → end approved                                            │
    │    Send back ─▶ Send "sent back" email (Mailpit) ───────────────────┘
    │                                                with reason
    ▼  Decision?  ── exclusive gateway ──▶  end approved
```

1. **Submit personal details** (applicant) — a React form collects first name,
   last name, age, and a product chosen from `api.restful-api.dev`. The task
   is assigned to the starting user via `camunda:assignee="${initiator}"`, so
   only that applicant sees it.
2. **Get price** — a service task using the
   [`http-connector`](#service-task--the-http-connector)
   calls `GET https://api.restful-api.dev/objects/{objectId}` and reads
   `data.price` from the JSON response into the `price` variable via Spin.
3. **Auto approval?** — a business rule task evaluates the
   [`auto-approval.dmn`](#dmn-decision-table) decision table (hit policy
   FIRST) on `age` and `price` and writes the result into `autoDecision`.
   Adults (age ≥ 18) picking cheap products (price &lt; 100) skip the human
   review; minors and expensive picks fall through.
4. **Review application** (civil servant) — a React form shows the submitted
   data and the fetched `price` read-only, and lets the reviewer **Accept**
   (sets `decision="approve"`) or **Send back** (sets `decision="sendback"`
   plus a `sendBackReason` variable). A **non-interrupting timer boundary
   event** fires every `PT2M` and triggers a [reminder email](#mailpit)
   without closing the task.
5. An exclusive gateway branches on `decision`. `approve` ends the process;
   any other value runs a **Send "sent back" email** service task (also via
   `http-connector` → Mailpit) and then loops back to the applicant task so
   they can fix the data based on the reason and resubmit.

## Architecture

```
  React SPA ──OIDC PKCE──▶ Keycloak ◀──Admin REST── CIB seven backend
   │   (keycloak-js)         │ ▲                    (identity provider plugin)
   │                         │ │ OAuth2 code flow
   │   Bearer JWT            │ │ (cib7-webapps client)
   ▼                         │ │
  /engine-rest               │ ▼
  /camunda/*  ─────▶  CIB seven 2.1 engine + REST + Cockpit/Tasklist/Admin
  (nginx / Vite proxy)  (Spring Boot, embedded engine, in-memory H2)
                                │
                                ├──▶  http-connector → api.restful-api.dev
                                │                       (product catalogue)
                                ├──▶  http-connector → Mailpit  (notifications
                                │                       + PDF attachments)
                                │                       :8025 (UI), :1025 (SMTP)
                                └──▶  http-connector → pdf-renderer → Gotenberg
                                                       (HTML → PDF, internal)
```

- The browser logs in against **Keycloak** (OIDC, PKCE) and then calls the
  same-origin path `/engine-rest/...` with a Bearer JWT on every request. In
  Docker, **nginx** proxies it to the backend; in dev, the **Vite** dev server
  does. No CORS configuration needed.
- The backend validates JWTs (Spring Security OAuth2 Resource Server) and the
  **CIB seven Keycloak Identity Provider Plugin** (`cibseven-keycloak` 2.1.0)
  reads users and groups from Keycloak's Admin REST API. Engine authorization
  is on, so `candidateGroups` on user tasks is enforced.
- The BPMN file lives in the backend and is **auto-deployed on startup**.
- The **Get price** service task calls the external API server-side, from the
  engine — via the official `http-connector`. The **Send reminder email**,
  **Send approval email**, and **Send "sent back" email** service tasks
  reuse the same `http-connector` against Mailpit's `/api/v1/send` JSON
  endpoint. The **Generate approval PDF** task uses the same connector
  against a tiny Node sidecar (`pdf-renderer/`) that fronts Gotenberg; the
  resulting PDF rides along as an attachment on the approval email.
- The **CIB seven webapps** (Cockpit / Tasklist / Admin) live under
  `/camunda/*` on the engine. A second `SecurityFilterChain` drives the
  Spring Security OAuth2 Authorization Code flow against the
  `cib7-webapps` Keycloak client and bridges the OIDC user into the
  engine's `IdentityService` via the cibseven-keycloak plugin's
  `ContainerBasedAuthenticationProvider` recipe.
- The database is **in-memory H2** — all data is lost when the backend stops.

## Project layout

```
cib7-react-poc/
├── docker-compose.yml
├── cib7/                           CIB seven 2.1 Spring Boot engine module
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/poc/cib7/
│       │   ├── Cib7PocApplication.java
│       │   ├── ConnectorConfiguration.java   registers the Connect plugin
│       │   ├── MailConfiguration.java        exposes ${mailApiBaseUrl}
│       │   ├── PdfConfiguration.java         exposes ${pdfApiBaseUrl}
│       │   ├── PdfHelper.java                @Component("pdf") base64↔byte[]
│       │   ├── AuthorizationBootstrap.java   grants /applicant engine perms
│       │   └── keycloak/                     Spring Security + Keycloak identity wiring
│       └── resources/
│           ├── application.yaml
│           ├── processes/                    BPMN + DMN (auto-deployed)
│           └── templates/                    FreeMarker payloads for connectors
├── pdf-renderer/                   Node sidecar (JSON-in/JSON-out over Gotenberg)
│   ├── server.js                   ~25 LOC Express wrapper
│   ├── package.json
│   └── Dockerfile
└── frontend/                       React + TypeScript + Vite app
    └── src/
        ├── api/
        │   ├── camundaClient.ts        typed /engine-rest client
        │   ├── bpmn.ts                 reads user tasks from BPMN XML
        │   └── objectsApi.ts           product list from api.restful-api.dev
        ├── pages/                      role-aware pages
        │   ├── ServicesPage.tsx        PartA — start a service
        │   ├── MyProcessesPage.tsx     PartA — applicant's instances + status
        │   ├── TasksPage.tsx           PartB — back-office task tree
        │   ├── IncidentsPage.tsx       PartB — open incidents + retry
        │   ├── TaskDetailPage.tsx      shared task form host
        │   └── CompletedProcessPage.tsx shared finished-process view
        └── forms/                      formKey → React component
```

---

## Run with Docker (recommended)

Requires Docker with Compose.

```bash
docker compose up --build
```

All URLs and credentials are listed in the
[Test logins & consoles](#test-logins--consoles) table above.

When the SPA loads it redirects to Keycloak's login form. Use `bart` / `bart`
to play the applicant or `homer` / `homer` to play the back-office reviewer.
Every `/engine-rest` call carries the JWT and the engine enforces
`candidateGroups` / `assignee` against the user's realm roles + group
membership.

As **Bart (PartA):** start a service on the **Services** page, fill the
applicant form, and watch the row appear under **My processes** with a live
status. If the back office sends the case back, reopen the row to see the
reason banner and resubmit.

As **Homer (PartB):** the **Tasks** page groups every service's user tasks
with the active instances waiting at each step. Open a review task, then
**Accept** (process ends) or **Send back** with a reason (loops to the
applicant).

## Run locally (without Docker)

Requires **Java 17+** and **Node.js 20+**.

**Engine** (terminal 1):

```bash
cd cib7
mvn spring-boot:run
```

**Frontend** (terminal 2):

```bash
cd frontend
npm install
npm run dev
```

Then open <http://localhost:5173>. The Vite dev server proxies `/engine-rest`
to the backend on port 8080.

---

## How the form wiring works

Each BPMN user task carries a `camunda:formKey`:

```xml
<bpmn:userTask id="Task_SubmitDetails" name="Submit personal details"
               camunda:formKey="react:personal-details" />
```

The React app reads the task's `formKey` from the REST API, strips the
`react:` prefix, and looks the form id up in `src/forms/registry.ts`:

```ts
export const formRegistry = {
  'personal-details':  PersonalDetailsForm,
  'review-application': ReviewApplicationForm,
};
```

**To add a form:** add a user task with a new `camunda:formKey` in the BPMN,
create the component under `src/forms/`, and add one registry entry.

## Service task & the http-connector

The **Get price** service task uses the official
[`cibseven-connect-http-client`](https://mvnrepository.com/artifact/org.cibseven.connect/cibseven-connect-http-client)
connector — a CIB seven Connect SPI connector that wraps Apache HttpClient 5.
It is wired in two places:

- **Connect plugin** — `ConnectorConfiguration` registers
  `ConnectProcessEnginePlugin` so the engine parses `<camunda:connector>`.
  The `cibseven-connect-http-client` dependency declared in `cib7/pom.xml`
  registers the connector itself through the Connect SPI.
- **BPMN** — the service task carries the connector config inline. The
  response body comes back as the `response` variable; Spin (bundled with the
  CIB seven engine) parses it inline to pull `data.price` out:

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

The service task runs `asyncBefore`, so after the first form is confirmed the
job executor runs the connector — the **Auto approval?** DMN task runs next,
and depending on the outcome either the process ends or the **Review
application** task appears a moment later (use the Tasks page **Refresh**
button).

## DMN decision table

[`cib7/src/main/resources/processes/auto-approval.dmn`](cib7/src/main/resources/processes/auto-approval.dmn)
is deployed alongside the BPMN. It has two inputs — `age` (Integer) and
`price` (Double) — and a single string output `autoDecision`. Hit policy is
`FIRST`: minors always go to review, adults with cheap picks auto-approve,
everything else goes to review.

The Business Rule Task references it inline:

```xml
<bpmn:businessRuleTask id="Task_AutoDecide" name="Auto approval?"
                       camunda:decisionRef="auto-approval"
                       camunda:mapDecisionResult="singleEntry"
                       camunda:resultVariable="autoDecision" />
```

Auto-deploy picks up `*.dmn` automatically thanks to the list pattern in
[`application.yaml`](cib7/src/main/resources/application.yaml):

```yaml
camunda.bpm:
  deployment-resource-pattern:
    - classpath*:**/*.bpmn
    - classpath*:**/*.dmn
```

## Timer boundary event + Mailpit

The **Review application** user task carries a non-interrupting timer
boundary event (`R/PT2M`). Every two minutes while the task is open the
engine job executor fires a parallel branch into a **Send reminder email**
service task — the user task itself stays open and can fire again. The
service task is just the `http-connector` POSTing to Mailpit's
`/api/v1/send` JSON endpoint:

```xml
<camunda:inputParameter name="url">${mailApiBaseUrl}/api/v1/send</camunda:inputParameter>
<camunda:inputParameter name="method">POST</camunda:inputParameter>
<camunda:inputParameter name="payload">{
  "From": { "Email": "process@cib7-poc.local", "Name": "CIB7 POC" },
  "To":   [ { "Email": "civil-servant@cib7-poc.local" } ],
  "Subject": "Reminder: application waiting for review",
  "Text": "An application from ${firstName} ${lastName} has been waiting…"
}</camunda:inputParameter>
```

The same connector is reused on the send-back path to email the applicant
(`${initiator}@cib7-poc.local`) the rejection reason before looping back. The
`${mailApiBaseUrl}` variable is exposed by
[`MailConfiguration.java`](cib7/src/main/java/com/poc/cib7/MailConfiguration.java)
as a Spring bean, driven by the `MAIL_API_URL` env var
(`http://mailpit:8025` in Docker, `http://localhost:8025` for
`mvn spring-boot:run`).

[Mailpit](https://mailpit.axllent.org/) (`axllent/mailpit:latest`) is a tiny
SMTP server + web UI; the inbox at <http://localhost:8025> visualizes every
email the process sends.

## PDF generation (Gotenberg + pdf-renderer)

When the case ends in approval and the applicant provided an email, a
**Generate approval PDF** service task runs before the approval email. It is
yet another `http-connector` call — this time against the `pdf-renderer/`
sidecar at `${pdfApiBaseUrl}/render`, which takes JSON `{html, filename}`
and returns JSON `{filename, base64}`. The sidecar internally POSTs
`multipart/form-data` to **Gotenberg** (`gotenberg/gotenberg:8`, headless
Chromium) and base64-encodes the binary response — both warts that would
otherwise force the BPMN out of the connector pattern into a custom Java
delegate.

The decoded PDF lands in the `approvalPdfBytes` process variable as a
`byte[]` (not `String`), so the engine spills it into `ACT_GE_BYTEARRAY`
instead of the 4000-char `ACT_HI_VARINST.TEXT_` column. The
`approval-email.json.ftl` template re-encodes to base64 with
`${pdf.encode(approvalPdfBytes)}` when assembling the Mailpit attachment.
See [`docs/cib7.md` § Large process variables](docs/cib7.md#large-process-variables-bytes-typed)
for the rationale and the corresponding `PdfHelper` bean.

## Cockpit / Tasklist / Admin webapps with Keycloak SSO

The `cibseven-bpm-spring-boot-starter-webapp` dependency mounts the classic
CIB seven webapps at `/camunda/**`. A second Spring Security filter chain
(`com.poc.cib7.keycloak.webapp.WebappSecurityConfig`) drives an OAuth2
Authorization Code flow against the `cib7-webapps` Keycloak client; once the
user is logged in, `ContainerBasedAuthenticationFilter` calls
`KeycloakAuthenticationProvider.extractAuthenticatedUser`, which reads the
OIDC user and queries groups via the cibseven-keycloak plugin's read-only
`IdentityService` — the same identity model the `/engine-rest` Bearer-JWT
filter uses. The three Java files under `com/poc/cib7/keycloak/webapp/` are
the plugin's published recipe (`examples/sso-kubernetes`), repackaged.

URL split — internal vs browser-visible — is handled in
[`application.yaml`](cib7/src/main/resources/application.yaml) by listing
every OAuth2 endpoint explicitly (no `issuer-uri`, which would trigger OIDC
discovery against an URL the engine container can't reach):

```yaml
spring.security.oauth2.client.provider.keycloak:
  authorization-uri: http://localhost:8180/...  # browser
  token-uri:         http://keycloak:8080/...   # backend
  jwk-set-uri:       http://keycloak:8080/...   # backend
  user-info-uri:     http://keycloak:8080/...   # backend
```

Log in at <http://localhost:8080/camunda> as `homer` / `homer` for the admin
view, or `bart` / `bart` for the applicant view.

## REST endpoints used

All under `/engine-rest` (standard CIB seven / Camunda 7 REST API):

| Call | Purpose |
|------|---------|
| `GET  /process-definition?latestVersion=true` | List services (process definitions) |
| `GET  /process-definition/key/{key}/xml` | BPMN XML — read the model's user tasks |
| `POST /process-definition/key/{key}/start` | Start a process instance |
| `GET  /task` | List open tasks |
| `GET  /task?processInstanceId={id}` | Open tasks of one instance |
| `GET  /task/{id}` | Task details, including `formKey` |
| `GET  /task/{id}/form-variables` | Process variables for the form |
| `POST /task/{id}/complete` | Complete the task with typed variables |

---

## Deviations from the spec

This POC intentionally simplifies `docs/human-role-react-forms-spec.md`:

| Spec | This POC | Why |
|------|----------|-----|
| `cib:` BPMN namespace (§5.3) | Standard **`camunda:`** namespace | CIB seven 2.1 uses `camunda:` — confirmed against the official `cibseven-get-started-spring-boot` example. The spec's §5.3 is inaccurate. |
| BFF between React and engine (D11) | React calls **`/engine-rest` directly** with a Bearer JWT | Bearer auth + the resource-server filter chain in front of the engine is the production-acceptable middle ground until a BFF is added. |
| Form manifest + publish-time validation (§11) | Omitted | The BPMN is a single static file, not dynamically generated. |
| Single `json` Spin variable (§10) | Plain typed variables (`firstName`, `objectId`, `price`, `decision`, …) | Simpler; no Spin needed for a POC. |
| Separate edit/view form components (§8.2–8.3) | One component per form | The "entry then review" flow already gives one edit form and one read-only review form. |
| IdP groups → candidate groups | **Keycloak groups `/applicant` (assignee via `${initiator}`) and `/civil-servant` (candidateGroup `civil-servant` — slash stripped by the plugin)** | Implemented via `cibseven-keycloak` 2.1.0 with `useGroupPathAsCamundaGroupId: true`. The plugin maps path `/civil-servant` to engine group id `civil-servant`; see [`docs/cib7.md`](docs/cib7.md#bpmn-files). |

For a production system the spec's BFF and manifest validation would be
reinstated.

## Notes & limitations

- In-memory H2 means **process state is lost on backend restart**.
- Keycloak runs in `start-dev` mode with its own in-memory H2 — the realm is
  re-imported from `keycloak/realm-export.json` on every container start, so
  user-created users/groups are also lost on restart.
- The **Get price** service task calls the public `api.restful-api.dev` — an
  internet connection is needed for that step.
- The DMN's `PT2M` timer cycle is a demo value — switch to `PT8H` / `PT1D`
  for anything real, otherwise Mailpit fills up fast.
- Mailpit's storage is non-persistent (no volume mounted); restarting the
  container empties the inbox.
