# Reference service pack

The services, texts, branding and users of one eRegistrations deployment,
as data for the eRegistrations core. This pack ships two services,
`vehicleRegistration` and `businessRegistration`. The core (engine,
backend, portal, mobile app, MCP sidecar, bus) is a separate, versioned
product: this pack names the core release it runs on in
[`docker/core.conf`](docker/core.conf), and a core update reaches it as a
version bump, not a merge.

Core and pack meet only through the formats of the core's
[platform API](https://github.com/krixerx/cib7-react-poc/blob/main/docs/platform-api.md);
`pack.yaml` says which platform API this pack is written for.

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
| `docker/` | the pack's images, each a thin layer on a core image; `core.conf` names the core release |
| `.claude/skills/service-builder/` | the service-builder of that core release (vendored by `scripts/update-core.sh`) |
| `.github/workflows/` | `check.yml` (the core's pack-check at that release), `publish.yml` (the images) |

## Changing a service

Specs first: edit the markdown under `docs/business/services/<service>/`,
run `/service-builder` in Claude Code, and commit the spec with what it
generated. Never hand-edit a generated file. A pull request runs the core's
pack checks; a merge to `main` publishes the images.

## Moving to another core release

```bash
scripts/update-core.sh <x.y.z>   # CORE_VERSION + the matching service-builder
```

Renovate proposes new core releases by changing `docker/core.conf`; that pull
request fails `check.yml` until `scripts/update-core.sh` has run in it,
because the vendored service-builder must come from the same release. Read
the core release notes first: a new platform API major means changing the
pack.

## Building the images by hand

```bash
. docker/core.conf
docker build -f docker/engine.Dockerfile \
  --build-arg ENGINE_CORE=$CORE_IMAGES/cib7-poc-cib7-core:$CORE_VERSION .
```

The same for `frontend`, `backend`, `mcp` and `mobile` (`FRONTEND_CORE`,
`BACKEND_CORE`, `MCP_CORE`, `MOBILE_CORE`, core images
`cib7-poc-<name>-core`).
