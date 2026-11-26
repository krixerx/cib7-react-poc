---
name: patchbay-conventions
description: Rules for writing, changing or reviewing the Patchbay integration service in patchbay/ (core packages, Dockerfile) and a pack's own Patchbay packages in packs/<pack>/patchbay/ with its docker/patchbay.Dockerfile. Use before adding or changing an outbound integration, when a mail-send or pdf-render call fails, and when running /review.
---

# Patchbay conventions

The engine reaches an external system only through a Patchbay integration, at
`${patchbayBaseUrl}/api/v1/integrations/<slug>`, and the backend only at `${backendBaseUrl}`.
Patchbay maps the platform's neutral payload to the external API and holds that system's address and
credentials, which come from the environment. `patchbay/README.md` carries the longer reasoning and
the steps for changing a package; `docs/platform-api.md` ("Integrations offered to a pack") the
models.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule | Where |
|---|---|---|---|
| PB1 | BLOCKER P2 | A package folder is a console Export unpacked over it; no JSON is written by hand, and no file under `services/<slug>.versions/` is ever edited | [PB1](#pb1); `patchbay/README.md` |
| PB2 | BLOCKER P1 | A pack's slugs, definition, contract and connection ids start with `pack-`; its layer zips each folder to `/import/50-<name>.zip` after the core's `10-`/`20-` | `docs/platform-api.md`; `PackIntegrationTest`; `scripts/patchbay-layer-check.sh` |
| PB3 | BLOCKER P1 | A package holds no `connections/`, no `secrets/` and no `target.baseUrl`; addresses and secrets come from `MAPPER_URL_*`, `MAPPER_HEADER_*`, `MAPPER_SECRET_*` (a pack's only with the `PACK_` prefix) | `PackIntegrationTest`; `docs/security.md` §9 |
| PB4 | BLOCKER P2 | `/config` is a `tmpfs`: a console change is gone after a restart until it is exported into git | [PB4](#pb4) |
| PB5 | BLOCKER P2 | `mail-send` and `pdf-render` v1 are platform API: an optional field is a minor; a new required field, a rename, a removal, a type change or a renamed slug is a major | `docs/platform-api.md` "Versioning of the models" |
| PB6 | BLOCKER P1 | No BPMN connector sets `X-API-Key` or `X-Internal-Token`; the engine adds each only for its own destination | `OutboundCredentialsInterceptorTest`; `PackIntegrationTest` |

## Details

### PB1

**BLOCKER P2.** Packages come from exports only.

Patchbay refuses to import a version number that already exists with other content, so a hand edit
of a published version breaks the next start, and a hand-written draft is never tested against the
real system. Change an integration in a local console of the pinned release, test it, publish it,
export it and unpack the ZIP over the folder. One integration per package keeps a change reviewable.

### PB4

**BLOCKER P2.** Configuration is ephemeral.

An existing `activeVersion` survives an import, so with a persistent `/config` a redeploy carrying a
newer version would leave the old one live without an error. Every start therefore imports the
packages into an empty folder, and the login comes from the environment. The console is for watching
calls, testing and trying a change; it is not where a change is kept.

## Unverified

Patchbay 0.1.0's `required` checks presence only: `"to": []` passes it and the mail system refuses
it. Whether it checks required fields inside list items has not been tested.
