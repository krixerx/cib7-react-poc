# Reference service pack

The services, texts, branding and users of one eRegistrations deployment,
as data for the core in this repository. This pack ships two services,
`vehicleRegistration` and `businessRegistration`. The core's tests,
`docker compose`, `mvn spring-boot:run` and `npm run dev` run on it.

Core and pack meet only through the formats of the
[platform API](../../docs/platform-api.md); `pack.yaml` says which platform
API this pack is written for. Another customer's pack is another directory
under `packs/` with the same layout.

## What is here

| Path | What |
|---|---|
| `pack.yaml` | name, version, platform API |
| `docs/business/services/<service>/` | the specs, the source of everything generated below, and `build/` (MCP manifests) |
| `engine/` | BPMN, DMN, variable policies, form schemas, payload templates, PDF and email documents |
| `backend/` | registries and their migrations, co-signing, fees, document categories |
| `frontend/` | catalog, form definitions, texts in every language |
| `branding/` | logo, colours, portal name |
| `keycloak/` | the realm's users (they join the core's groups) |
| `docker/` | the pack's images, each a thin layer on a core image |

## Changing a service

Specs first: edit the markdown under `docs/business/services/<service>/`,
run `/service-builder` in Claude Code, and commit the spec with what it
generated as one change. Never hand-edit a generated file. Then check the
pack:

```bash
scripts/pack-check.sh packs/reference
```

A merge to `main` publishes the pack's images (`docker-publish.yml`, job
`pack-layers`) on the core images of the same commit.

## Building the images by hand

From the repository root, on a core image of your choice:

```bash
docker build -f packs/reference/docker/engine.Dockerfile   --build-arg ENGINE_CORE=docker.io/krixerx/cib7-poc-cib7-core:<tag> packs/reference
```

The same for `frontend`, `backend`, `mcp` and `mobile` (`FRONTEND_CORE`,
`BACKEND_CORE`, `MCP_CORE`, `MOBILE_CORE`, core images
`cib7-poc-<name>-core`).
