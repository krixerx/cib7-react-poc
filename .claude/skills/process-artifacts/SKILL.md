---
name: process-artifacts
description: Rules the engine imposes on the generated service artifacts in a pack's engine/ (BPMN, DMN, FreeMarker payloads, formKey, candidate groups). Use when a generated artifact misbehaves, a service will not deploy, a form is not found, when reviewing a change under packs/test/ and when running /review. To generate artifacts, use service-builder; never hand-edit them.
---

# Process artifacts

`/service-builder` writes these files from the spec and its self-check covers most of what can go
wrong, so this skill is the reasoning behind that check, for debugging and review. When an artifact
is wrong, fix the spec and regenerate; a hand edit is lost on the next run.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule |
|---|---|---|
| [P1](#p1) | BLOCKER P2 | `formKey` resolves to a form; nothing validates the id at deploy time |
| [P2](#p2) | BLOCKER P2 | One engine deployment per `processes/<service>/` folder; a service's DMNs live in it |
| [P3](#p3) | BLOCKER P2 | Group ids have no leading slash |
| [P4](#p4) | BLOCKER P2 | A payload template cannot `<#include>` |
| [P5](#p5) | BLOCKER P2 | DMN files declare `historyTimeToLive` |
| [P6](#p6) | BLOCKER P2 | The namespace is `camunda:`, not `cib:` |

## Details

### P1

**BLOCKER P2.** `formKey` resolves to a form; nothing validates the id at deploy time.

**`formKey` to form.** A user task carries `camunda:formKey="react:<form-id>"`;
the SPA strips `react:` and `forms/resolve.ts` picks the form: a TSX component
from `frontend/src/forms/registry.ts` if the spec says `Renderer: tsx`,
otherwise the schema renderer, which fetches the pack's
`/pack/forms/<form-id>.json` (the pack's image layer,
`packs/test/docker/frontend.Dockerfile`, puts `packs/test/frontend`
there on top of the pack-less core image; the Vite dev server serves
`PACK_DIR` or the core test pack the same way). Nothing validates the id at deploy time,
so a wrong id shows up only on the task page, as "No form definition found".

### P2

**BLOCKER P2.** One engine deployment per `processes/<service>/` folder; a service's DMNs live in it.

**One engine deployment per `processes/<service>/` folder.**
`ServiceDeployments.java` scans `classpath*:processes/*/` (the pack directory
is on the classpath, the jar holds none) in `@PostConstruct`
with duplicate filtering that redeploys a changed service whole
(`deployChangedOnly=false`: a BPMN deployed without its DMN breaks
`decisionRefBinding="deployment"`). A service's DMNs must live in the same folder because
business rule tasks use `camunda:decisionRefBinding="deployment"`.

### P3

**BLOCKER P2.** Group ids have no leading slash.

**Group ids have no leading slash.** The Keycloak group path is
`/civil-servant`, but the cibseven-keycloak plugin maps it to the engine group
id `civil-servant`. `candidateGroups` and authorization grants must use the
slash-less form; the realm export keeps the canonical paths.

### P4

**BLOCKER P2.** A payload template cannot `<#include>`.

**A payload template cannot `<#include>`.** The engine's FreeMarker script
engine has no template loader, so layouts live in the pack's `documents/`
and a payload calls `${documents.html("<name>", execution)}` (PDF, `.ftlh`,
HTML-escaped automatically) or `${documents.text(...)}` (email body); the
`documents` bean (`DocumentRenderer.java`) renders them with the shared
`_brand.ftlh` and the pack's `branding/`.

### P5

**BLOCKER P2.** DMN files declare `historyTimeToLive`.

**DMN files must declare `historyTimeToLive`** (CIB seven 2.2 hard rule).

### P6

**BLOCKER P2.** The namespace is `camunda:`, not `cib:`.

**Namespace is `camunda:`, not `cib:`.** CIB seven 2.2 keeps the Camunda 7
namespace.
