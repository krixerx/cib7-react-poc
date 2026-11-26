# Security rules

**When to read this:** before adding or changing an endpoint, an engine
authorization grant, a process variable, a token link, an integration, a
container, or a generated service artifact. These rules are mandatory; a change
that breaks one needs a written reason in this file, not just in a commit.

Contents:

1. [Demo exemptions](#demo-exemptions)
2. [A service pack is reviewed code](#a-service-pack-is-reviewed-code)
3. [Engine authorization](#1-engine-authorization)
4. [Process variables are untrusted input](#2-process-variables-are-untrusted-input)
5. [Capability links](#3-capability-links)
6. [External facts need proof](#4-external-facts-need-proof)
7. [Backend endpoint classes](#5-backend-endpoint-classes)
8. [Object references need an ownership check](#6-object-references-need-an-ownership-check)
9. [Encoding at every boundary](#7-encoding-at-every-boundary)
10. [Ingress](#8-ingress)
11. [Internal network](#9-internal-network)
12. [MCP and LLM agents](#10-mcp-and-llm-agents)
13. [Containers and supply chain](#11-containers-and-supply-chain)
14. [Secrets](#12-secrets)
15. [Every security rule has a test](#13-every-security-rule-has-a-test)

---

## Demo exemptions

This is a demo stack. The following are accepted on purpose and are not
findings:

- The seeded Keycloak users and their passwords (`admin`/`admin`,
  `homer`/`homer`, `bart`/`bart`), the Keycloak bootstrap admin, open
  self-registration without email verification (`verifyEmail` is off so a
  visitor can register on the public deployment, where mail only reaches
  Mailpit), and Keycloak's `start-dev` mode.
- Development defaults for secrets in the compose files (`:-...-change-me`).
  `DefaultSecretsGuard` warns about them at startup; a real deployment sets
  `APP_REQUIRE_REAL_SECRETS=true`, which turns the warning into a refusal to
  start.
- Self-signed TLS certificates.
- Postgres without TLS between the services and the database; it is reachable
  only on the internal `db` network. The database passwords have dev defaults
  like the other secrets (the backend's `DefaultSecretsGuard` covers its own).
- The shared demo inbox. On a TLS deployment, `/mailpit` serves Mailpit
  behind a Keycloak login (`mailpit-auth`, oauth2-proxy), and **any**
  logged-in user reads **every** process mail, including the capability
  links in it (owner confirmations, founder signatures, payment links). This
  knowingly breaks rule 3's "never returned to other parties" so visitors can
  watch the mail flow. The bounds that still hold: anonymous visitors get the
  login page, never the inbox (`deploy.sh` smoke-tests this), and Traefik
  routes neither `DELETE` nor Mailpit's send API, so a visitor cannot empty
  the inbox or plant a fake process mail. Mail never leaves the stack.
  A real deployment drops Mailpit for an SMTP relay.
- The `latest` tag on our own images: the `*_CORE` build-argument defaults
  in a pack's `docker/*.Dockerfile` (and `packs/reference/docker/`) and the
  `PACK_TAG` / `CORE_TAG` defaults in `deploy/docker-compose.yml`. The
  published pack images and the deploy workflow never use them (see rule 11).
  A real deployment replaces every `latest` with a pinned version.
- Patchbay, the integration service, logs to stdout only, not to Graylog
  (`docs/logging.md`, "What is not shipped"): Docker's `gelf` driver would
  need Graylog's input published on the host, and Patchbay has no GELF
  transport of its own yet. `docker compose logs patchbay` is its log, with
  json-file rotation.
- Patchbay's call log is on in the local `docker-compose.yml`
  (`MAPPER_CALL_LOG`). It keeps the last 100 payloads per integration in
  memory, which here means names, email addresses, capability links and
  whole PDFs, readable by anyone with the console login. The deploy kit
  turns it off by default (`PATCHBAY_CALL_LOG`); a real deployment keeps it
  off.

Everything below applies regardless. In particular, a self-registered user is
an ordinary applicant, so the authorization rules must hold against anyone on
the internet.

## A service pack is reviewed code

A pack is data (BPMN, DMN, FreeMarker, JSON), but its BPMN expressions and
templates run inside the engine. **A pack is reviewed code with the engine's
power, not a sandboxed plug-in.** A pull request that touches `packs/` is
reviewed like core code. The engine holds `INTERNAL_TASK_TOKEN`,
`PATCHBAY_API_KEY`, `LINK_SIGNING_SECRET`, the Keycloak client secrets and
the database login, and an expression that reaches the process environment
or the engine configuration can read them.

What is guaranteed, and by what:

- *At run time, in the engine.* Expressions and templates resolve by name
  only case variables, `execution`, Spin and the reserved beans of
  `ReservedBeansPlugin.RESERVED_NAMES`; no other Spring bean resolves, so
  `${environment.getProperty('INTERNAL_TASK_TOKEN')}` fails with an incident
  (`BeanExposureTest`). Payload templates (`engine/templates/`) refuse
  FreeMarker's `?new` and `?api`, with the settings `DocumentRenderer` already
  uses for `engine/documents/` (`PayloadTemplatesPlugin`,
  `PayloadTemplateHardeningTest`).
- *In the pack check.* `PackIntegrationTest` (tagged `pack`) refuses
  connector URLs other than the offered destinations, connectors that set
  `X-API-Key` or `X-Internal-Token`, and reflection-like expressions in BPMN,
  DMN, templates and documents (`getClass`, `forName`, `processEngine…`,
  `?new`, `?api`, `Execute`, `ObjectConstructor`). That last check is a text
  scan: it catches the honest mistake and the lazy attack, and a determined
  pack gets past it (string concatenation, `?interpret`, other paths to the
  same objects). It gates the reference pack, today the only pack, because
  `docker-publish.yml` builds the pack layers only after the `quality` job
  has run the pack tests.
- *Not guaranteed.* Through `execution` and Java method calls, a BPMN
  expression can still reach `execution.processEngine.processEngineConfiguration`
  (and its data source) and, by reflection from a string, the process
  environment. An image built without the pack check is not protected at
  all. Closing this at run time needs a JUEL resolver that refuses
  reflection types and `processEngine…` navigation; it is a recorded
  follow-up, required before a second pack is added or any pack is built
  outside this repository's CI (design D17 of `replace-esb-with-patchbay`).

What a pack cannot do as data (call another destination, set a credential,
reach a core host through its own integrations) is listed in
`docs/platform-api.md`, "What a pack cannot do".

## 1. Engine authorization

- No group gets a wildcard (`"*"`) grant on `TASK`, and no group except
  `cib7-admin` gets `UPDATE_TASK`, `UPDATE_INSTANCE`, `READ_INSTANCE`,
  `READ_TASK` or `READ_HISTORY` on `PROCESS_DEFINITION:*` unless the role
  really needs to see or change every case. Engine grants only ever add
  access; there is no implicit "own tasks only" filter on top of a wildcard.
- Applicants reach their own case through per-instance grants created for the
  initiator when the process starts (`InitiatorAuthorizationListener`), and
  their own tasks through the engine's default task authorizations for the
  assignee and candidates.
- Civil servants may read every case (it is their job), but may only work
  tasks routed to a group they belong to.
- Service accounts get the least privilege their caller needs; `cib7-admin`
  membership is for humans running the engine, not for integrations.
- `AuthorizationBootstrap` is the only place grants are created at startup.
  Every grant there has a test that a user outside the intended scope gets
  403 or an empty result.

## 2. Process variables are untrusted input

- A client (SPA, mobile app, MCP agent) may write only the variables its form
  declares. The allowlist per `formKey` and per start is generated from the
  spec into the pack's `engine/processes/<service>/variable-policy.json`
  and enforced by `VariableWritePolicyFilter` on every `/engine-rest` endpoint
  that writes variables: task `complete`, `submit-form` and `resolve`, and
  process `start` and `submit-form` are checked against the allowlist (start
  instructions and the `skip*` flags are refused); `POST
  /task/{id}/localVariables` (the MCP draft) is checked against the task's
  form allowlist. Every other endpoint that writes variables
  (`/process-instance/{id}/variables`, `/task/{id}/variables`,
  `/execution/{id}/localVariables`, `/message`, `/signal`, `/condition`,
  modification, migration, restart, external tasks, CMMN) is closed to
  everyone outside `cib7-admin`. A definition without a policy file accepts
  no client variables at all.
- The values are checked too. The service builder generates a JSON Schema
  per form and for the start variables from the spec's Fields table
  (the pack's `engine/processes/<service>/schemas/<form-id>.json`,
  shared rules such as email and personal code in
  `cib7/src/main/resources/schemas/core-v1.json`). The same filter checks
  completions and starts against it after the name check and answers 400
  with the failed rules, so "age 1 to 130" no longer holds only in the
  browser. Drafts are not value-checked; a form without a schema keeps the
  name check only.
- System-owned variables (DMN outputs, connector results, payment and consent
  state, anything a gateway depends on that no human decides) are never in an
  allowlist. They are set by the engine, a DMN, a connector response or the
  backend's service account. The names the core itself writes for every pack
  (the co-signing round and party, `paymentReceived`, `paymentReference`,
  `paidAmount`) are in `VariablePolicyRegistry.NEVER_WRITABLE` with the
  reserved beans and `initiator`: the engine refuses to start with a policy
  that lists one. A pack's own system state (DMN and connector results, the
  co-signing variables its consent descriptors name) is checked by
  `VariablePolicyFilesTest`, a pack check. A human decision (`decision`, `medicalResult`,
  ...) is writable only from the reviewer form that owns it, never from an
  applicant form or a start.
- Identity fields (`firstName`, `applicantName`, `applicantEmail`, ...) may be
  in an applicant form's allowlist, because the SPA resubmits the prefilled
  value; `IdentityValidationListener` rejects any value that differs from the
  validated token, so the allowlist entry cannot change them.
- Configuration beans used in expressions and templates (`patchbayBaseUrl`,
  `backendBaseUrl`, `frontendBaseUrl`, `pdf`, ...) are registered as reserved
  names that resolve before process variables (`ReservedBeansPlugin`,
  `ReservedBeansPluginTest`). A variable with the same name can never shadow
  them, so a client cannot redirect a connector. They are also the only
  beans that resolve at all (`BeanExposureTest`).

## 3. Capability links

A capability link is a URL that grants an action without a login (co-owner
confirmation, founder signature, payment).

- Generated on the server, never in the browser. Either at least 128 bits from
  `SecureRandom`, or an HMAC-SHA256 signature over the case id, the party, a
  round counter and an expiry, keyed with a secret only the server holds.
- Bound to one case, one party, one round and an expiry. A resubmission bumps
  the round, which invalidates every earlier link.
- Never stored in raw form anywhere another party can read it: not in a
  process variable, not in a response to anyone except the party it was
  issued to. Status endpoints return other parties' state, never their tokens
  or email addresses.
- Compared in constant time. Unknown, expired and wrong-round tokens all get
  the same 404.

How it is built: the engine's `links` bean (`CapabilityLinks`) signs
`processInstanceId|partyId|purpose|round|expiresAt` with HMAC-SHA256 under
`LINK_SIGNING_SECRET` while rendering the email, and nothing stores the
result. Party ids, the confirmations map and `consentRound` are written by
`ConsentPartiesListener` on the applicant's submit task, which ignores any
token, party id or approval the client sent; the round is the submission
time in milliseconds, so it changes on every resubmission without reading a
value the client could have written. The backend's `CapabilityLinkVerifier`
checks signature, expiry, purpose, process and current round, and the token
alone names the case and party an action applies to. Payment links carry
round 0: they are bound to the case's applicant and expire after 30 days,
and the payment session state decides whether anything is still payable.
The SPA gets a payment link for the signed-in applicant's own case from
`GET /api/cases/{id}/payment-link`, which mints one only for the user who
started the case.

## 4. External facts need proof

A fact that originates outside the system (a payment, a bank transfer, a
signature by a third party) is accepted only from the system that is the
authority for it, with proof: a callback signed with HMAC over the reference,
the amount and the outcome, verified in constant time against the amount the
server computed. A browser call saying "it happened" is never proof. The demo
uses `MockPaymentProvider` as that authority.

The same holds for documents: an issued document is a fact only the engine
can state. Every document category says who may create it
(`backend/documents.json`, `DocumentCategories`): the user-facing endpoints
(`/api/documents/upload-url` flow, `/stage`, `/{case}/attachments`) accept only
`by: applicant` categories, and `server-upload` only `by: system` ones. Before
this, a signed-in applicant could file their own PDF as their case's
`generated-certificate`, which the mobile wallet and the approval signal show
as issued (`DocumentsControllerWebTest.registerRefusesSystemCategoriesAndPersistsNothing`).

How it is built: `POST /api/public/payments/{token}/checkout` only creates a
`PaymentSession` (random session id, amount from `FeeSchedule`, which
computes it from the pack's `backend/payment/` rule; a tiered fee may only
depend on a variable the engine sets, checked by `PackConformanceTest`; status
`PENDING`) and returns the provider's page. The provider reports the outcome
to `POST /api/public/payments/callback` with `X-Provider-Signature`, the hex
HMAC-SHA256 of the raw body (session, reference, amount, currency, status)
under `PAYMENT_PROVIDER_SECRET`. A bad signature is 401; a reference,
amount or currency that differs from the session is 400; a repeated PAID
callback is a no-op. Only a valid PAID callback for a pending session
correlates `PaymentReceived`. In production only the `mockprovider` package
is replaced by a real provider's client.

## 5. Backend endpoint classes

Every `/api/**` path belongs to exactly one class, chosen by its prefix:

| Prefix | Who calls it | Authentication |
|---|---|---|
| `/api/public/**` | a citizen following a capability link, or anonymous read-only reference data (public registries, the service list) | the capability token (rule 3); read-only reference data only if it holds no personal data |
| `/api/internal/**` | the engine, directly | `X-Internal-Token`, which the engine attaches only to its calls to the backend's `/api/internal/`; never routed by the ingress |
| everything else | the SPA, the mobile app, MCP | Keycloak JWT with the `cib7-rest-api` audience |

Inside the JWT class, `/api/statistics/**` additionally requires the
`statistics-viewer` realm role, read from `realm_access.roles` by
`RealmRoleAuthorities`. Other JWT endpoints decide access per object
(rule 6) rather than by role.

The engine is the only caller of `/api/internal/**`. Its
`OutboundCredentialsInterceptor` adds `X-Internal-Token` (`INTERNAL_TASK_TOKEN`)
only when a connector's target, parsed and normalised, has the origin of
`backendBaseUrl` and a path under `/api/internal/`, and removes it from every
other request, a header the BPMN set included
(`OutboundCredentialsInterceptorTest`). The backend compares it in constant time
and answers 401 to a missing, empty or wrong token
(`InternalTokenAuthenticationFilterTest`). Patchbay never holds the token and
shares no network with the backend (rule 9). The token still stops any other
container on the networks the backend shares (`edge`, `app`).

`SecurityConfig` ends with a deny-all chain, so a new path outside these
prefixes is rejected rather than silently open. An endpoint that writes data
or returns personal data is never public unless it verifies a capability.

## 6. Object references need an ownership check

Every id or key a caller passes (process instance, document id, S3 key, case
reference) is checked against the caller before use: case access through
`CaseAccessService` (engine `startUserId` or reviewer role), storage keys by
prefix (`pending/<user>/`, `process/<case>/`). Unknown and forbidden look the
same (404).

Deleting a draft case is the one place an applicant removes engine state.
Applicants hold no DELETE grant, so `DELETE /api/cases/{id}`
(`DraftCaseController`) does it with the service account, and only for the
user who started the case (`isCaseStarter`, so reviewers cannot), only while
the case is active and only while none of its user tasks has been completed
(409 otherwise). Once the first form is submitted the case is on record and
stays.

## 7. Encoding at every boundary

- FreeMarker JSON payloads: every user-supplied value through `?json_string`.
- FreeMarker HTML (PDF and email bodies): every user-supplied value through
  `?html`.
- Connector URLs: user-supplied values never go into a URL path unencoded.
  Validate the format first (VIN, civil id), and encode the segment.
- React: no `dangerouslySetInnerHTML` with user data.

## 8. Ingress

- Every response carries `Strict-Transport-Security` (when served over TLS),
  `Content-Security-Policy`, `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer` (capability
  tokens live in URL paths).
- `/api/public/**` and the Keycloak login endpoints are rate limited.
  `/api/public/**` (10 r/s, burst 20 per IP) and `/mcp` (5 r/s) are limited
  in nginx and in Traefik. Keycloak is not routed by this stack's ingress
  by default (it is published on its own port), so its login limit exists
  only as the commented `keycloak-login` router in `routes.yml.example`;
  until a deployment routes Keycloak through Traefik, the realm's
  brute-force detection is the only throttle there.
- The ingress routes only the paths the SPA needs. `/api/internal/**` returns
  404 at the edge.
- Where these headers are set: the frontend and mobile nginx for everything
  they answer (`frontend/nginx.conf`, `frontend/docker/snippets/`), Traefik's
  `security-headers` / `api-headers` middlewares on every TLS router (HSTS is
  only sent there). The engine webapps under `/camunda` keep the nonce-based
  CSP the engine sends; nginx and Traefik add a CSP only where it has none.

## 9. Internal network

- Containers that have no reason to talk share no network. The engine
  reaches external systems only through Patchbay (network `integration`,
  engine and Patchbay only) and the backend directly over `app`, which they
  already shared. Patchbay reaches Mailpit (`mail`) and the PDF renderer
  (`pdf`); only the PDF renderer reaches Gotenberg. The map is in
  `docs/architecture.md` § Networks.
- Patchbay shares no network with the backend, Keycloak, Postgres, RustFS or
  Graylog. Its console's Test button and a pack's integration can call any
  host Patchbay reaches, so what it cannot reach is the bound. Graylog
  therefore gets each GELF shipper on its own network (`gelf-cib7`,
  `gelf-backend`, `gelf-pdf`, `gelf-mcp`) and sits on none of Patchbay's.
- Patchbay requires `X-API-Key` on every integration call (`INBOUND_API_KEY`,
  compared in constant time by Patchbay). The engine adds it from
  `PATCHBAY_API_KEY` only to calls under Patchbay's `/api/v1/integrations/`
  and removes it everywhere else (`OutboundCredentialsInterceptor`,
  `OutboundCredentialsInterceptorTest`). Caller headers never reach the
  external system. Patchbay's console and `/admin` take its own login
  (`PATCHBAY_ADMIN_USER`/`PATCHBAY_ADMIN_PASSWORD`); in phase 1 they are on
  `127.0.0.1` only (an SSH tunnel on a server).
- Patchbay holds each external system's address and credentials, from the
  environment, never from a package. The core's packages live in
  `patchbay/packages/`, a pack's in `<pack>/patchbay/`; a pack's ids start
  with `pack-` and its packages carry no connection, secret or base URL
  (`PackIntegrationTest`). A pack's addresses and secrets come from the
  host's `patchbay-pack.env` (`MAPPER_URL_PACK_*`, `MAPPER_HEADER_PACK_*`,
  `MAPPER_SECRET_PACK_*`), which `deploy.sh` refuses when it holds another
  key or a URL naming a core service. `/config` is not persisted, so the
  packages in git are the only source of integrations.
- A pack's BPMN calls only `${patchbayBaseUrl}/api/v1/integrations/<slug>`
  for a core slug or one of its own, and `${backendBaseUrl}` with the paths
  the platform API offers (`PackIntegrationTest`).
- Gotenberg renders with JavaScript disabled and a deny list covering every
  internal host.

## 10. MCP and LLM agents

- The MCP sidecar validates the token's signature, issuer and audience before
  forwarding it.
- Every `/mcp` request needs a valid token, the handshake included
  (`mcp/src/auth/requireBearer.ts`). A request without one gets HTTP 401
  before any tool runs, so a new tool or method can never answer anonymously.
- Applicant-written text returned to an LLM is wrapped as untrusted data and
  never placed where it reads as instructions.
- Tools that complete a task tell the agent, in the tool description, to show
  the decision to the human and get explicit confirmation before calling.
  Engine authorization (rule 1), not the agent's judgement, decides what the
  call may do.

## 11. Containers and supply chain

- Every image we build runs as a non-root user. A pack's layers set no
  `USER` of their own and inherit the core image's; the `pack-layers` job
  of `docker-publish.yml` refuses to push a layer whose image user is
  empty, `root` or `0`.
- Base and third-party images are pinned to a version, never `latest`.
  Patchbay is one of them: `patchbay/Dockerfile` builds `cib7-poc-patchbay`
  FROM `krixerx/patchbay:0.1.0`, and a pack's `docker/patchbay.Dockerfile`
  builds on that core image; both run as Patchbay's own non-root user
  (uid 10001). Exception: our own images in `deploy/docker-compose.yml` default to
  `latest` (`PACK_TAG`, `CORE_TAG`) for a quick single-machine evaluation;
  the deploy workflow always pins the deployed commit's SHA for both.
  Likewise a pack's `docker/*.Dockerfile` defaults its `*_CORE` base image
  to `latest` for a bare `docker build`; `docker-publish.yml` always passes
  the core image of the same commit, by its short SHA. Both are demo
  exemptions (see above) and go away in a real deployment.
- No container mounts the Docker socket in the deploy bundle or the prod
  overlay (both run Traefik on the file provider). Only the profile-gated
  dev Traefik in `docker-compose.yml` mounts it, read-only.
- CI scans dependencies and images, and images are published only after the
  quality workflow passes (`docker-publish.yml` calls `quality.yml`, then a
  Trivy image scan; `security.yml` runs `npm audit` and a Trivy filesystem
  scan; Dependabot proposes updates). CodeQL static analysis of our own
  code (`codeql.yml`) is available but runs only when started by hand.

## 12. Secrets

- No secret is committed. Every `.env` file is git-ignored.
- The realm export holds `${VAR}` placeholders only.
- Secrets are compared in constant time and never logged.

## 13. Every security rule has a test

A fix for any rule above lands with a negative test: the wrong user, a forged
token, an unlisted variable or an internal path from outside gets refused.

The tests that hold the outbound and pack boundary:

| Rule | Test | What it refuses |
|---|---|---|
| Pack trust, 2 | `BeanExposureTest` | a deployed BPMN or template naming a bean outside the reserved names |
| Pack trust | `PayloadTemplateHardeningTest` | a payload template using `?new` or `?api`; no request is sent |
| Pack trust, 9 | `PackIntegrationTest` | other connector destinations, credential headers in a connector, reflection-like expressions, a pack package with non-`pack-` ids, a connection, a secret or a base URL, a pack layer that zips anything but its packages |
| 2 | `ReservedBeansPluginTest` | a process variable shadowing a reserved bean |
| 5, 9 | `OutboundCredentialsInterceptorTest` | `X-Internal-Token` or `X-API-Key` on any other destination, host-suffix and userinfo tricks, a header the BPMN set |
| 5 | `InternalTokenAuthenticationFilterTest` | a missing, empty, wrong or wrong-length `X-Internal-Token` |
| 12 | `DefaultSecretsGuardTest` (engine and backend) | starting with a `-change-me` default when real secrets are required |

Still proved by hand on the running stack, not by a test: Patchbay's 401
without `X-API-Key`, and that Patchbay cannot reach the backend, Keycloak or
Graylog (task 8.2 of `replace-esb-with-patchbay`).
