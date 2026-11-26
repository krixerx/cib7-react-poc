# patchbay

The core's integration service: [Patchbay](https://hub.docker.com/r/krixerx/patchbay), pinned to a
release, plus the core packages. The engine calls an external system only through a Patchbay
integration, by slug, at `${patchbayBaseUrl}/api/v1/integrations/<slug>` with the `X-API-Key`
header. Patchbay maps the platform's own payload to the external system's API and back, and holds
the system's address and credentials, which come from the environment.

| Package | Integration | Payload definitions | Contract and connection | Maps to |
|---|---|---|---|---|
| `packages/10-core-mail/` | `mail-send` | `mail-send`, `mail-send-result` | `mailpit` (Mailpit `swagger.json`, tag `v1.31.3`) | `POST /api/v1/send` |
| `packages/20-core-pdf/` | `pdf-render` | `pdf-render`, `pdf-render-result` | `pdf-renderer` (`pdf-renderer/openapi.json`) | `POST /render` |

The two payloads are part of the platform API (`docs/platform-api.md`, "Integrations offered to a
pack"); renaming or removing a field is a platform major.

## The image

`Dockerfile` zips each folder under `packages/` into `/import/<folder>.zip` and builds
`cib7-poc-patchbay` FROM `krixerx/patchbay` with `MAPPER_IMPORT_DIR=/import`. Patchbay imports the
ZIPs at every start in file-name order, so the folder prefix is the order: core `10-`, `20-`, a pack's
`50-` (its `docker/patchbay.Dockerfile` layers them on this image). The image runs as `mapper`
(uid 10001), as upstream does.

It needs, at least:

| Variable | What |
|---|---|
| `MAPPER_URL_MAILPIT` | Mailpit's base URL, with its webroot if it has one (`http://mailpit:8025/mailpit`) |
| `MAPPER_URL_PDF_RENDERER` | `http://pdf-renderer:8088` |
| `INBOUND_API_KEY` | the key the engine sends as `X-API-Key` (its `PATCHBAY_API_KEY`) |
| `MAPPER_ADMIN_USER`, `MAPPER_ADMIN_PASSWORD` | the console and admin API login |

`/config` is a `tmpfs` owned by uid 10001 (`/config:uid=10001` in compose), not a volume. Every start
imports the packages into an empty folder, so each integration goes live with the version that was
live in its package, and anything changed in the console is gone after a restart. The packages in
git are the only source of integrations.

## Changing a package

Never edit the JSON by hand, and never edit a file under `services/<slug>.versions/`: a published
version is immutable, and Patchbay refuses to import a version number that exists with other
content.

1. Run the image locally with the variables above and the console published on loopback only
   (`-p 127.0.0.1:<port>:8080`, `MAPPER_URL_*` pointing at a running Mailpit or pdf-renderer).
2. Change the integration in the console (or through `/admin/api` with `curl -u`), and test it with
   **Test** against the real system.
3. **Publish** it. That writes the next version (`1.1.0`), which goes live in place of the old one.
4. **Export** that one integration (Integrations list, or
   `POST /admin/api/export {"integrations":["<slug>"]}`) and unpack the ZIP over its folder here,
   so the folder holds the new version file next to the old ones.
5. Rebuild the image and check `GET /admin/integrations` lists it active and nothing as broken.

`scripts/build.mjs` is how `10-core-mail` and `20-core-pdf` were first built: it replays the whole
build through `/admin/api` against a fresh Patchbay (`node scripts/build.mjs <baseUrl> <user:password>
<mailpit swagger.json> <pdf-renderer/openapi.json> publish`), so the export step above can be
repeated without the console. Keep it in step when you change a core integration by hand.

A new integration of the core gets its own folder (`30-core-<name>/`) with its own package. One
integration per package keeps a change reviewable on its own.

Packages never carry connections or secrets; a folder with `connections/` or `secrets/` is wrong.
