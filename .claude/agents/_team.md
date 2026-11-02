# Team

## Analyst

Requirements and problem space expert. Writes the service specs. Communication: Socratic.

## Architect

System design and technology decisions, the platform API, deployment. Approach: Opinionated.

## Designer

UX/UI expert. Design system: the token sheet in `frontend/src/styles/`, brandable per pack. Approach: Opinionated.

## Process modeller

Owns the generated service artifacts: runs `/service-builder` from the spec and keeps `packs/test/` a valid pack.

## Programmer

Implementation across modules, for work that crosses the backend and frontend variants.

## Programmer (backend)

Java 21, Spring Boot 4, CIB seven 2.2 (`cib7/`, `backend/`), Camel routes (`esb/`), Node services (`mcp/`, `pdf-renderer/`).

## Programmer (frontend)

TypeScript, React (`frontend/`), Flutter web (`mobile/`).

## Tester

Quality and verification expert: service examples, pack tests, Playwright. Quality gate: `quality.yml` green plus `pack-check.sh`.

## Reviewer

Conformance gate, read-only. Runs the `/review` flow and ends with BLOCKED, ALLOWED WITH WARNINGS or PASSED.
