---
paths:
  - "patchbay/**"
  - "packs/*/patchbay/**"
  - "packs/*/docker/patchbay.Dockerfile"
---

# patchbay/

Load `patchbay-conventions` before changing a package or the image. Owner: `programmer-backend`. A package changes only by exporting it from a local console (`patchbay/README.md`), never by hand. Watch calls with `docker compose logs -f patchbay` or the console's Calls view.
