# Docs

**Audience:** AI agents (Claude, Copilot, Cursor, …) and developers reading the
repo for the first time. The top-level `README.md` is the human onboarding
entry point; these docs add the deeper, structured detail an AI needs to make
correct changes without re-deriving everything from source.

**Repo shape — monorepo.** CIB seven engine module (Spring Boot, `cib7/`),
frontend (React + Vite, `frontend/`), the vendored connector JAR (`lib/`), the
Keycloak realm export (`keycloak/`), Docker orchestration
(`docker-compose.yml`), and these docs (`docs/`) all live in a single git
repository and are versioned, built, and shipped together.

Each topic doc below starts with a **When to read this** block and a stable
table of contents so it can be opened, skimmed, and closed in one pass.
(This index file is the exception — it exists to be skimmed in full.)

---

## Map — which doc answers what

| If you need to … | Read |
|---|---|
| Understand the runtime topology, request flow, deployment model | [`architecture.md`](architecture.md) |
| Touch React code: pages, forms, the form registry, the REST client, auth | [`frontend.md`](frontend.md) |
| Touch Java code: Spring Boot wiring, engine config, the BPMN file, the connector, Keycloak | [`cib7.md`](cib7.md) |
| Understand the auth chain end-to-end (SPA → JWT → engine identity) | [`architecture.md` § Security posture](architecture.md#security-posture) + [`cib7.md` § Authentication and authorization](cib7.md#authentication-and-authorization) + [`frontend.md` § Authentication](frontend.md#authentication) |
| Reference the original long-form design spec | [`human-role-react-forms-spec.md`](human-role-react-forms-spec.md) |
| Run / build the app, see the high-level overview | top-level [`../README.md`](../README.md) |

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

## How to keep these docs healthy

- Update the doc in the same PR that changes the code it describes.
- Keep each file focused on its scope — don't duplicate content across files,
  cross-link instead.
- Headings are stable anchors. Don't rename a section without checking inbound
  links from sibling docs.
- If a section grows beyond ~80 lines, consider splitting it into its own file.
