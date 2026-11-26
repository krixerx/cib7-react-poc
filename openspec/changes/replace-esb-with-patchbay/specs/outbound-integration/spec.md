# Spec Delta

## Purpose

Defines how the process engine reaches anything outside itself: external systems through named Patchbay integrations that accept the platform's own neutral payloads, and the business backend directly, with each credential sent only to the destination it belongs to.

## ADDED Requirements

### Requirement: External systems are reached through named integrations
The engine SHALL call every system outside the stack's core services (mail, PDF rendering, a pack's own external systems) through the integration service by slug, at `<integration base>/api/v1/integrations/<slug>`. The engine SHALL hold no address, credential or field mapping of any external system.

#### Scenario: A mail task calls the mail integration
- **WHEN** a service task sends a notification email
- **THEN** the engine posts the mail payload to the `mail-send` integration and the integration service delivers it to the configured mail system

#### Scenario: The mail system moves
- **WHEN** an operator changes the mail system's base URL in the deployment configuration and restarts the integration service
- **THEN** mail is delivered to the new address without any change to the engine, a pack or a BPMN file

### Requirement: Mail is sent in the platform's neutral mail model
The `mail-send` integration SHALL accept a JSON object with `from` (`email`, `name`), `to` (a list of `email`, `name`), `subject`, `text`, and optionally `attachments` (a list of `filename`, `contentType`, `base64`). It SHALL answer with `messageId`. It SHALL reject a payload that lacks a required field with 400 and the external system SHALL not be called. An empty `to` list SHALL send no mail; the integration service does not check list length, so the mail system refuses it.

#### Scenario: Mail with an attachment
- **WHEN** the engine posts a `mail-send` payload with one PDF attachment
- **THEN** the recipient's mailbox holds one message with the given subject, text and the attachment under its filename

#### Scenario: Missing recipient
- **WHEN** the engine posts a `mail-send` payload without `to`
- **THEN** the integration answers 400 listing the missing field and no mail is sent

#### Scenario: Empty recipient list
- **WHEN** the engine posts a `mail-send` payload with `"to": []`
- **THEN** the call answers 400 from the mail system and no mail is sent

### Requirement: PDFs are rendered in the platform's neutral render model
The `pdf-render` integration SHALL accept `html` and `filename` and SHALL answer with `filename` and `base64`, the rendered PDF's bytes in base64.

#### Scenario: Render a certificate
- **WHEN** the engine posts `{html, filename}` to `pdf-render`
- **THEN** the answer carries the same `filename` and a `base64` value that decodes to a PDF document

### Requirement: The engine calls the backend directly
The engine SHALL call the backend's offered paths (`/api/internal/**` and `/api/public/**`) at the backend's own address, not through the integration service.

#### Scenario: Store a generated document
- **WHEN** a service task stores a generated PDF through `/api/internal/documents/server-upload`
- **THEN** the request reaches the backend directly and the backend records the document

### Requirement: The internal token goes only to the backend's internal API
The engine SHALL add `X-Internal-Token` to an outbound request only when the request's target is the backend's address under `/api/internal/`. It SHALL remove any `X-Internal-Token` a BPMN connector sets on any other request, and no BPMN file or template SHALL contain the token.

#### Scenario: Internal call carries the token
- **WHEN** a connector calls `<backend>/api/internal/documents/move-pending`
- **THEN** the request carries the configured internal token

#### Scenario: Public backend call carries no token
- **WHEN** a connector calls `<backend>/api/public/registry/vehicles/<vin>`
- **THEN** the request carries no `X-Internal-Token`

#### Scenario: A connector tries to send the token elsewhere
- **WHEN** a connector targets the integration service or any other host and sets an `X-Internal-Token` header itself
- **THEN** the request leaves the engine without that header

### Requirement: The integration key goes only to the integration service
The engine SHALL add the integration service's inbound key as `X-API-Key` only to requests whose target is the integration service's address, and SHALL remove an `X-API-Key` set by a connector on any other request. The integration service SHALL refuse an integration call without the right key with 401.

#### Scenario: Call without the key
- **WHEN** a container other than the engine calls `/api/v1/integrations/mail-send` without `X-API-Key`
- **THEN** the integration service answers 401 and no mail is sent

#### Scenario: Backend call carries no integration key
- **WHEN** a connector calls the backend
- **THEN** the request carries no `X-API-Key`

### Requirement: Client-written variables cannot redirect an outbound call
The names of the integration base address and the backend base address SHALL win over process variables of the same name in BPMN expressions and templates.

#### Scenario: A form writes a variable named like the base address
- **WHEN** a process variable named `patchbayBaseUrl` or `backendBaseUrl` exists on a case
- **THEN** connectors still resolve the configured addresses

### Requirement: Integrations are configured only from versioned packages and the environment
The integration service SHALL start with exactly the integrations held in the image's packages and the base URLs and secrets set in the deployment's environment. Changes made in its console SHALL not survive a restart.

#### Scenario: Redeploy with a new mapping version
- **WHEN** a package with a newer published version of `mail-send` is deployed
- **THEN** after the restart that version is live

#### Scenario: Console edit is not kept
- **WHEN** an operator changes a mapping in the console and the service restarts
- **THEN** the live mapping is again the one from the packages

#### Scenario: A package cannot go live
- **WHEN** an integration in a package is missing its base URL or secret at start
- **THEN** the integration service lists it as broken and the deploy smoke test fails
