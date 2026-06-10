# Docs

**Audience:** AI agents (Claude, Copilot, Cursor, …) and developers reading the
repo for the first time. The top-level `README.md` is the human onboarding
entry point; these docs add the deeper, structured detail an AI needs to make
correct changes without re-deriving everything from source.

**Repo shape — monorepo.** CIB seven engine module (Spring Boot, `cib7/`),
frontend (React + Vite, `frontend/`), the Keycloak realm export (`keycloak/`),
Docker orchestration (`docker-compose.yml`), and these docs (`docs/`) all live
in a single git repository and are versioned, built, and shipped together.

Each topic doc below starts with a **When to read this** block and a stable
table of contents so it can be opened, skimmed, and closed in one pass.
(This index file is the exception — it exists to be skimmed in full.)

---

## Map — which doc answers what

The docs are split into two layers:

- **Platform docs** (this folder) — how the platform works. Cross-cutting,
  service-agnostic.
- **Business docs** ([`business/`](business/)) — what the platform delivers.
  One folder per service under [`business/services/`](business/services/),
  each describing its BPMN flow, forms, integrations, and roles.

| If you need to … | Read |
|---|---|
| Understand the runtime topology, request flow, deployment model | [`architecture.md`](architecture.md) |
| Deploy this stack to a server (admin-facing) | [`deployment.md`](deployment.md) |
| Touch React code: pages, forms, the form registry, the REST client, auth | [`frontend.md`](frontend.md) |
| Touch Java code: Spring Boot wiring, engine config, BPMN auto-deploy, the connector, Keycloak | [`cib7.md`](cib7.md) |
| Understand the auth chain end-to-end (SPA → JWT → engine identity) | [`architecture.md` § Security posture](architecture.md#security-posture-poc) + [`cib7.md` § Authentication and authorization](cib7.md#authentication-and-authorization) + [`frontend.md` § Authentication](frontend.md#authentication) |
| Reference the form contract between BPMN and React | [`human-role-react-forms-spec.md`](human-role-react-forms-spec.md) |
| Change a specific business service (flow, forms, integrations) | [`business/services/<service>/README.md`](business/services/) |
| Add a new business service | [`business/services/`](business/services/) — copy an existing service folder as a template |
| Regenerate a service's flow diagram from its BPMN | [`../scripts/bpmn-to-mermaid.mjs`](../scripts/bpmn-to-mermaid.mjs) |
| Run / build the app, see the high-level overview | top-level [`../README.md`](../README.md) |

### Services

| Service | Process key | Doc |
|---|---|---|
| Vehicle Registration | `vehicleRegistration` | [`business/services/vehicle-registration/`](business/services/vehicle-registration/README.md) |
| Estonian OÜ Registration | `businessRegistration` | [`business/services/business-registration/`](business/services/business-registration/README.md) |

## Conventions

- **Code style — Google.** Java follows the
  [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html);
  TypeScript / React follows the
  [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html).
  When in doubt, match the surrounding code.
- **BPMN files** live under `cib7/src/main/resources/processes/` and are
  auto-deployed on startup. See [`cib7.md`](cib7.md#bpmn-files) for the
  one-file-per-process rule and how `formKey` wires a user task to a React form.
- **Form id contract.** A BPMN user task carries `camunda:formKey="react:<id>"`;
  the React app strips the `react:` prefix and looks `<id>` up in
  `frontend/src/forms/registry.ts`. Details: [`frontend.md`](frontend.md#forms).
- **Service docs are per-service.** Anything specific to a single business
  service (its flow, forms, integrations, variables, roles) belongs in
  `docs/business/services/<service>/`, not in the cross-cutting platform
  docs. The cross-cutting docs describe how the platform works in general;
  the service folder describes what one service does in particular.
- **Flow diagrams are generated, not hand-written.** Each service README
  embeds a mermaid diagram between `<!-- bpmn-diagram:start -->` and
  `<!-- bpmn-diagram:end -->` markers. Re-run
  [`../scripts/bpmn-to-mermaid.mjs`](../scripts/bpmn-to-mermaid.mjs) after
  changing the BPMN; don't hand-edit the block.

## How to keep these docs healthy

- Update the doc in the same PR that changes the code it describes.
- Keep each file focused on its scope — don't duplicate content across files,
  cross-link instead.
- Cross-cutting vs service-specific: if a change touches one service only,
  update that service's folder. If it changes how every service must behave,
  update the cross-cutting doc and link from the affected services.
- Headings are stable anchors. Don't rename a section without checking inbound
  links from sibling docs.
- If a section grows beyond ~80 lines, consider splitting it into its own file.
- After editing a BPMN file, regenerate the diagram for that service before
  committing.
