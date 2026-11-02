---
name: service-spec
description: Write or change the markdown specification of a service under a pack's docs/business/services/<service>/ (README, forms/, service-tasks/, decisions/, data/, consent.md), the source everything else is generated from. Use before writing a spec, when a spec is incomplete or contradictory, and before running service-builder.
---

# Writing a service specification

The spec is the source of truth. BPMN, DMN, templates, form definitions, policies and MCP manifests
are generated from it by `/service-builder`, so a gap in the spec becomes a guess in the service.

```
docs/business/services/<service>/  ->  /service-builder  ->  engine/, backend/, frontend/, build/
        written by the analyst         run by the process modeller      checked by pack-check.sh
```

Start from `.claude/skills/service-builder/spec-template/`; it is the authoritative list of sections.
`service-builder` holds the generation rules and the self-check; this skill does not repeat them.

## Rules

- **Say everything the modeller needs:** every variable, field, decision row and ending. If a required field is missing, stop and ask; nobody fills it with a guess.
- **Write examples from the policy, never from generated output.** An example copied from what the generator produced proves only that the generator ran. The sections and their tests are in `process-testing`.
- **Spec and generated output change in one commit.** Hand edits to generated files are lost.
- **One service, one folder**, with one file per form, service task and decision, named by its id.
- **Services change in their pack's repository.** Here, `packs/test/` changes only when a core change needs it to, and it stays a valid pack (`scripts/pack-check.sh`).
- **State fee, documents and consent are spec sections too**: they generate `backend/payment/`, `documents.json` and `backend/consent/`.
