# Tech Stack

`pom.xml`, `package.json`, `pubspec.yaml` and the Dockerfiles hold the exact versions.

- **Engine (`cib7/`):** Java 21, Spring Boot 4, CIB seven 2.2, Postgres, Flyway. Maven.
- **Backend (`backend/`):** Java 21, Spring Boot 4, Postgres, Flyway, RustFS S3. Maven.
- **Frontend (`frontend/`):** TypeScript, React, Vite, plain CSS on tokens, Vitest. Node 24, npm 11 for lockfiles.
- **MCP (`mcp/`):** TypeScript, Vitest. Node 24.
- **Mobile (`mobile/`):** Flutter web, no platform folders committed.
- **Bus (`esb/`):** Apache Camel JBang, YAML routes, no build step.
- **pdf-renderer (`pdf-renderer/`):** Node 24, Express, over Gotenberg.
- **Packs (`packs/test/`):** BPMN, DMN, FreeMarker, form definitions, generated from markdown specs.
- **Platform:** Keycloak OIDC, Graylog over GELF, nginx or Traefik ingress, Docker Compose.
