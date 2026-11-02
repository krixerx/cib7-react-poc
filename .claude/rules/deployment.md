---
paths:
  - "docker-compose*.yml"
  - "deploy/**"
  - "keycloak/**"
  - "traefik/**"
  - "**/Dockerfile*"
  - "**/nginx.conf"
  - ".github/workflows/**"
---

# Deployment files

Load `deployment-conventions` before changing these. Owner: `architect`. `deploy/` holds nothing of a pack, and smoke tests must not name a pack's services.
