# Spec Delta

## Purpose

Defines how operators reach the integration service's console on a public deployment, and which of its paths the public ingress serves, so the console is usable from the internet while the integration API and the service's internals stay closed.

## ADDED Requirements

### Requirement: The console is served under a sub-path
On a TLS deployment the integration console SHALL be served at `/patchbay/` on the public host, with every page, asset and admin call working under that prefix. `/patchbay` without a trailing slash SHALL redirect to `/patchbay/`.

#### Scenario: Open the console
- **WHEN** an operator opens `https://<host>/patchbay/` and logs in
- **THEN** the integrations list loads, and its assets and admin calls are fetched under `/patchbay/`

#### Scenario: Missing trailing slash
- **WHEN** a browser opens `https://<host>/patchbay`
- **THEN** it is redirected to `https://<host>/patchbay/`

### Requirement: The console needs the admin login
Every console and admin path under `/patchbay/` SHALL answer 401 to a request without the admin login. The admin login SHALL be set from the deployment's environment, and a deploy with an empty or development-default admin password SHALL abort.

#### Scenario: Anonymous visitor
- **WHEN** an anonymous request asks for `https://<host>/patchbay/`
- **THEN** the answer is 401 with a Basic challenge

#### Scenario: Default password on a server
- **WHEN** `deploy.sh` runs with the admin password unset or at its development default
- **THEN** the deploy aborts before starting the stack

### Requirement: Console changes need the console's own origin
Admin writes SHALL be accepted only from the console's own origin behind the proxy, so a page on another site cannot use the browser's saved login.

#### Scenario: Write from the console
- **WHEN** the logged-in console at `https://<host>/patchbay/` publishes an integration
- **THEN** the write is accepted

#### Scenario: Write from another site
- **WHEN** a page on another origin sends an admin write with the browser's saved login
- **THEN** the write is refused

### Requirement: Only console paths are routed
The public ingress SHALL route only the console page, its assets and the admin API under `/patchbay/`. The integration API (`/patchbay/api/**`), the health endpoints and any other path SHALL not reach the integration service.

#### Scenario: Integration call from the internet
- **WHEN** a request from the internet posts to `https://<host>/patchbay/api/v1/integrations/mail-send`
- **THEN** the ingress answers 404 and the integration service receives nothing

#### Scenario: Health endpoint from the internet
- **WHEN** a request from the internet asks for `https://<host>/patchbay/actuator/health/readiness`
- **THEN** the ingress answers 404

### Requirement: The console path is rate limited and carries security headers
Requests under `/patchbay/` SHALL be rate limited per client address, and every answer SHALL carry HSTS, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and a Content-Security-Policy that forbids framing.

#### Scenario: Password guessing
- **WHEN** one address sends login attempts faster than the limit
- **THEN** the excess requests get 429 without reaching the integration service

### Requirement: The deploy proves the console gate
The deploy smoke tests SHALL fail when anonymous `/patchbay/` does not answer 401, when `/patchbay/api/v1/integrations/<any>` is answered by anything but the ingress's 404, or when the integration service lists a broken integration.

#### Scenario: Gate lost
- **WHEN** a routing change makes `/patchbay/` answer 200 anonymously
- **THEN** the deploy reports FAIL and exits non-zero
