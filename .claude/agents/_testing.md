# Testing

## Test types

- **Java:** JUnit in `cib7/` and `backend/`, H2 through the same Flyway migrations. Tests that hold for any pack are tagged `pack`.
- **Service examples:** examples in the spec run as tests; `process-testing` has the table.
- **Frontend and MCP:** Vitest beside the code.
- **Mobile:** `flutter test`, one file, not in CI.
- **pdf-renderer:** no tests.
- **E2E:** Playwright in `e2e/`, public VM by default, not in CI.
- **Security:** `security.yml`, CodeQL, image scans in `docker-publish.yml`.

## Quality gate

Done means the commands under "Quality gates" in `CLAUDE.md` pass (the same as `quality.yml`), `scripts/pack-check.sh` passes when the change touches a pack format or `packs/test/`, and every security fix has a negative test. CI also checks the Keycloak theme sync and the service-builder package.
