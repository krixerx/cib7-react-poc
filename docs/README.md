# Docs

**Audience:** AI agents (Claude, Copilot, Cursor, …) and developers reading the
repo for the first time. The top-level `README.md` is the human onboarding
entry point; these docs add the deeper, structured detail an AI needs to make
correct changes without re-deriving everything from source.

**Repo shape — the core.** The CIB seven engine module (Spring Boot, `cib7/`),
the backend, the frontend (React + Vite, `frontend/`), the mobile app, the MCP
sidecar, the bus, the core Keycloak realm (`keycloak/`), Docker orchestration
(`docker-compose.yml`) and these docs (`docs/`) are versioned and released
together as core images. The services are service packs in their own
repositories (the reference pack:
[krixerx/eregistrations-reference-pack](https://github.com/krixerx/eregistrations-reference-pack));
`packs/test/` is a frozen copy of it for the core's tests and local stack, and
`platform-api.md` is the contract.

Each topic doc below starts with a **When to read this** block and a stable
table of contents so it can be opened, skimmed, and closed in one pass.
(This index file is the exception — it exists to be skimmed in full.)

---

## Map — which doc answers what

The docs are split into two layers:

- **Core docs** (this folder): how the platform works. Cross-cutting and
  service-agnostic; they hold for every service pack.
- **Service docs** live in each service pack's repository, one folder per
  service under its `docs/business/services/`: the spec of the BPMN flow,
  forms, integrations and roles, which the service-builder generates the
  pack's files from. The reference pack's:
  [eregistrations-reference-pack/docs/business/services](https://github.com/krixerx/eregistrations-reference-pack/tree/main/docs/business/services);
  the core test pack keeps a frozen copy in
  [`../packs/test/docs/business/services/`](../packs/test/docs/business/services/).

| If you need to … | Read |
|---|---|
| Understand the runtime topology, request flow, deployment model | [`architecture.md`](architecture.md) |
| Write or change a service pack, or core code that reads one: formats, versions, what a pack may do, `pack-check` | [`platform-api.md`](platform-api.md) |
| Deploy this stack to a server (admin-facing) | [`deployment.md`](deployment.md) |
| Deploy from GitHub Actions, or work out why a CI run failed | [`ci-cd.md`](ci-cd.md) |
| Find a service's logs, add a log statement, reach Graylog | [`logging.md`](logging.md) |
| Change the statistics page, its counting rules or who may see it | [`statistics.md`](statistics.md) |
| Touch React code: pages, forms, the form registry, the REST client, auth | [`frontend.md`](frontend.md) |
| Touch Java code: Spring Boot wiring, engine config, BPMN auto-deploy, the connector, Keycloak | [`cib7.md`](cib7.md) |
| Understand the auth chain end-to-end (SPA → JWT → engine identity) | [`architecture.md` § Security posture](architecture.md#security-posture-poc) + [`cib7.md` § Authentication and authorization](cib7.md#authentication-and-authorization) + [`frontend.md` § Authentication](frontend.md#authentication) |
| Add or change an endpoint, grant, variable, token link, integration or container (mandatory rules) | [`security.md`](security.md) |
| Reference the form contract between BPMN and React | [`human-role-react-forms-spec.md`](human-role-react-forms-spec.md) |
| Change a specific business service (flow, forms, integrations) | its spec in the pack repository (`docs/business/services/<service>/`), then `/service-builder` there; the reference pack: [eregistrations-reference-pack](https://github.com/krixerx/eregistrations-reference-pack) |
| Add a new business service | the pack's spec template (`.claude/skills/service-builder/spec-template/`), in the pack repository |
| Regenerate a service's flow diagram from its BPMN | the skill's `tools/bpmn-to-mermaid.mjs` in a pack, [`../scripts/bpmn-to-mermaid.mjs`](../scripts/bpmn-to-mermaid.mjs) here |
| Run / build the app, see the high-level overview | top-level [`../README.md`](../README.md) |

### Services of the reference pack

The core's examples and its test pack use these two; their specs live in the
pack repository (the copies under `packs/test/` are frozen).

| Service | Process key | Spec |
|---|---|---|
| Vehicle Registration | `vehicleRegistration` | [`vehicle-registration/`](https://github.com/krixerx/eregistrations-reference-pack/tree/main/docs/business/services/vehicle-registration) |
| Estonian OÜ Registration | `businessRegistration` | [`business-registration/`](https://github.com/krixerx/eregistrations-reference-pack/tree/main/docs/business/services/business-registration) |

## Conventions

- **Code style — Google.** Java follows the
  [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html);
  TypeScript / React follows the
  [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html).
  When in doubt, match the surrounding code.
- **BPMN files** live in a pack's `engine/processes/<service>/` (here
  `packs/test/engine/processes/`) and are auto-deployed on startup, one
  deployment per folder. See [`cib7.md`](cib7.md#bpmn-files) for the rules
  and how `formKey` wires a user task to a form.
- **Form id contract.** A BPMN user task carries `camunda:formKey="react:<id>"`;
  the React app strips the `react:` prefix and draws the pack's form
  definition `/pack/forms/<id>.json`, or a TSX component from
  `frontend/src/forms/registry.ts` for the rare `Renderer: tsx` form.
  Details: [`frontend.md`](frontend.md#forms).
- **Service docs are per-service and live in the pack.** Anything specific to
  a single business service (its flow, forms, integrations, variables, roles)
  belongs in its pack's `docs/business/services/<service>/`, not in these
  core docs. The core docs describe how the platform works for every pack;
  the service folder describes what one service does in particular.
- **Flow diagrams are generated, not hand-written.** Each service README
  embeds a mermaid diagram between `<!-- bpmn-diagram:start -->` and
  `<!-- bpmn-diagram:end -->` markers, regenerated by the service-builder
  after changing the BPMN; don't hand-edit the block.

## How to keep these docs healthy

- Update the doc in the same PR that changes the code it describes.
- Keep each file focused on its scope — don't duplicate content across files,
  cross-link instead.
- Core vs service-specific: if a change touches one service only, it
  belongs to that service's pack. If it changes how every service must
  behave, it is a core change: update the core doc here, and
  `platform-api.md` when a pack format changes.
- Headings are stable anchors. Don't rename a section without checking inbound
  links from sibling docs.
- If a section grows beyond ~80 lines, consider splitting it into its own file.
- Keep `packs/test/` frozen unless a core change needs it to change, and
  keep it a valid pack (`scripts/pack-check.sh`).
