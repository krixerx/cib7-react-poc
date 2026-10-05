# Security rules

**When to read this:** before adding or changing an endpoint, an engine
authorization grant, a process variable, a token link, an integration, a
container, or a generated service artifact. These rules are mandatory; a change
that breaks one needs a written reason in this file, not just in a commit.

Contents:

1. [Demo exemptions](#demo-exemptions)
2. [Engine authorization](#1-engine-authorization)
3. [Process variables are untrusted input](#2-process-variables-are-untrusted-input)
4. [Capability links](#3-capability-links)
5. [External facts need proof](#4-external-facts-need-proof)
6. [Backend endpoint classes](#5-backend-endpoint-classes)
7. [Object references need an ownership check](#6-object-references-need-an-ownership-check)
8. [Encoding at every boundary](#7-encoding-at-every-boundary)
9. [Ingress](#8-ingress)
10. [Internal network](#9-internal-network)
11. [MCP and LLM agents](#10-mcp-and-llm-agents)
12. [Containers and supply chain](#11-containers-and-supply-chain)
13. [Secrets](#12-secrets)
14. [Every security rule has a test](#13-every-security-rule-has-a-test)

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
- In-memory H2 (tracked as `TODOS.md` T1).
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

Everything below applies regardless. In particular, a self-registered user is
an ordinary applicant, so the authorization rules must hold against anyone on
the internet.

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
  spec into `cib7/src/main/resources/processes/<service>/variable-policy.json`
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
- System-owned variables (DMN outputs, connector results, payment and consent
  state, anything a gateway depends on that no human decides) are never in an
  allowlist. They are set by the engine, a DMN, a connector response or the
  backend's service account. A human decision (`decision`, `medicalResult`,
  ...) is writable only from the reviewer form that owns it, never from an
  applicant form or a start.
- Identity fields (`firstName`, `applicantName`, `applicantEmail`, ...) may be
  in an applicant form's allowlist, because the SPA resubmits the prefilled
  value; `IdentityValidationListener` rejects any value that differs from the
  validated token, so the allowlist entry cannot change them.
- Configuration beans used in expressions and templates (`busBaseUrl`,
  `frontendBaseUrl`, `pdf`, ...) are registered as reserved names that resolve
  before process variables (`ReservedBeansPlugin`). A variable with the same
  name can never shadow them.

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

How it is built: `POST /api/public/payments/{token}/checkout` only creates a
`PaymentSession` (random session id, amount from `FeeSchedule`, status
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
| `/api/public/**` | a citizen following a capability link, or anonymous read-only reference data | the capability token (rule 3); read-only reference data only if it holds no personal data |
| `/api/internal/**` | the engine, through the ESB | `X-Internal-Token`; never routed by the ingress |
| everything else | the SPA, the mobile app, MCP | Keycloak JWT with the `cib7-rest-api` audience |

Inside the JWT class, `/api/statistics/**` additionally requires the
`statistics-viewer` realm role, read from `realm_access.roles` by
`RealmRoleAuthorities`. Other JWT endpoints decide access per object
(rule 6) rather than by role.

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

- Containers that have no reason to talk share no network. Only the engine
  reaches the ESB; only the PDF renderer reaches Gotenberg. The map is in
  `docs/architecture.md` § Networks. Graylog is the one deliberate extra
  member: it joins the networks its GELF shippers sit on (including the
  engine–ESB `bus`), so that logging does not need one network connecting
  every shipper to every other.
- The ESB adds `X-Internal-Token` only for callers that authenticate to it:
  every route first requires `X-Bus-Token` equal to `BUS_TOKEN`
  (`esb/routes/bus-auth.yaml`) and strips it before forwarding. Exception to
  rule 12: that comparison is plain string equality, because Camel's simple
  language has no constant-time compare and the bus has no listener outside
  the internal network.
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

- Every image we build runs as a non-root user.
- Base and third-party images are pinned to a version, never `latest`.
  Exception: our own images in `deploy/docker-compose.yml` default to
  `${IMAGE_TAG:-latest}` for a quick single-machine evaluation; the deploy
  workflow always pins `IMAGE_TAG` to a commit SHA.
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
