# Spec Delta

## Purpose

Defines what a service pack may call from its BPMN and what its own integration packages may contain, so a pack can add external integrations without reaching the core's secrets, the core's services or another pack's definitions.

## ADDED Requirements

### Requirement: A pack's connectors call only offered destinations
Every connector URL in a pack's BPMN SHALL be either `${patchbayBaseUrl}/api/v1/integrations/<slug>`, where the slug is a core integration (`mail-send`, `pdf-render`) or one the pack's own packages declare, or `${backendBaseUrl}<path>` for a backend path the platform API offers. URLs SHALL carry no query, fragment or `..`. A pack check SHALL refuse any other URL.

#### Scenario: Connector to an offered backend path
- **WHEN** a pack's BPMN calls `${backendBaseUrl}/api/internal/documents/server-upload`
- **THEN** the pack check passes

#### Scenario: Connector to an undeclared integration
- **WHEN** a pack's BPMN calls `${patchbayBaseUrl}/api/v1/integrations/pack-unknown` and no package of the pack declares `pack-unknown`
- **THEN** the pack check fails naming the connector and the slug

#### Scenario: Connector to a literal host
- **WHEN** a pack's BPMN calls `http://backend:8085/api/internal/cases`
- **THEN** the pack check fails

#### Scenario: The removed bus address
- **WHEN** a pack's BPMN still uses `${busBaseUrl}`
- **THEN** the pack check fails and says the pack needs platform API 3.0

### Requirement: A pack's connectors set no credential headers
A pack's BPMN connectors SHALL set neither `X-Internal-Token` nor `X-API-Key`.

#### Scenario: Connector sets the internal token
- **WHEN** a pack's BPMN connector has an `X-Internal-Token` header
- **THEN** the pack check fails

### Requirement: A pack's expressions name no engine bean
Expressions and templates in a pack SHALL resolve by name only case variables, `execution`, Spin and the reserved beans the platform API lists. Any other Spring bean SHALL not resolve. Payload templates SHALL refuse FreeMarker's `?new` and `?api` built-ins at run time.

#### Scenario: A connector input reads an engine secret through a bean
- **WHEN** a deployed BPMN's connector input is `${environment.getProperty('INTERNAL_TASK_TOKEN')}`
- **THEN** the expression does not resolve, the task fails with an incident and no request carries the value

#### Scenario: A payload template constructs a class
- **WHEN** a payload template contains `"freemarker.template.utility.ObjectConstructor"?new()`
- **THEN** rendering fails and no request is sent

### Requirement: The pack check refuses reflection-like expressions
The pack check SHALL refuse a pack whose connector inputs or outputs, payload templates or documents use `getClass`, `forName`, `execution.processEngine` (or a deeper navigation from it), `?new` or `?api`. This check, not the engine, is what keeps a pack from reaching the engine's configuration through reflection; a pack is reviewed code with the engine's power.

#### Scenario: A connector input reads the environment through reflection
- **WHEN** a pack's connector input is `${''.getClass().forName('java.lang.System').getMethod('getenv', ''.getClass()).invoke(null, 'INTERNAL_TASK_TOKEN')}`
- **THEN** the pack check fails naming the connector

#### Scenario: A template navigates to the engine configuration
- **WHEN** a pack's payload template contains `${execution.processEngine.processEngineConfiguration}`
- **THEN** the pack check fails naming the template

### Requirement: A pack's packages use their own names
Every integration slug, payload definition id, contract id and connection id in a pack's packages SHALL start with `pack-`, so a pack package can never replace a core definition or integration on import.

#### Scenario: A pack package redefines the core mail model
- **WHEN** a pack package contains a payload definition with the id `mail-send`
- **THEN** the pack check fails

### Requirement: A pack's packages hold no address or secret
A pack's packages SHALL contain no `connections/` or `secrets/` entries and no version or draft with a literal base URL. Base URLs and secrets of a pack's external systems SHALL come only from the deployment's environment.

#### Scenario: A version file with a literal base URL
- **WHEN** a pack package's version file has `target.baseUrl`
- **THEN** the pack check fails

#### Scenario: A package with a secret
- **WHEN** a pack package contains a file under `secrets/`
- **THEN** the pack check fails

### Requirement: A pack's integrations cannot reach core services
A pack's connection base URLs SHALL point to systems outside the stack. The deployment SHALL refuse to start when a pack connection's base URL names a core host, and the integration service SHALL share no network with the backend, the identity provider, the database, the object store or the log store.

#### Scenario: A pack connection points at the backend
- **WHEN** a deployment sets a pack connection's base URL to `http://backend:8085`
- **THEN** the deploy aborts before starting the stack and names the variable

#### Scenario: Network isolation
- **WHEN** a pack integration is configured with a core host's address anyway
- **THEN** the call fails because the integration service cannot reach that host

### Requirement: Pack checks run on any pack
The checks in this capability SHALL be tagged `pack` so `scripts/pack-check.sh <pack>` runs them against any pack directory.

#### Scenario: Pack check on the reference pack
- **WHEN** `scripts/pack-check.sh packs/reference` runs
- **THEN** the connector and package checks run and pass
