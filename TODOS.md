# TODOS

Captured during plan reviews. Items here are deferred work the team agreed to revisit, with enough context that anyone picking them up understands the motivation and the current state.

---

## T1 — Replace in-memory H2 with PostgreSQL + volume

**What:** Swap `cib7/`'s in-memory H2 database for PostgreSQL with a persistent docker-compose volume. Add `spring.datasource.*` config in `cib7/src/main/resources/application.yaml`, drop the `org.h2:h2` runtime dependency from `cib7/pom.xml`, add the `postgresql` driver, add a `postgres` service and a `cib7-data` volume to `docker-compose.yml`.

**Why:** Today the engine loses all process state on restart. The `seed-history` compose service exists only to work around this for the autofill demo. With persistence, the seed dependency dissolves and the autofill story works across compose restarts, internal pilots, and CI-driven end-to-end runs.

**Pros:**
- Process state survives `docker compose restart`.
- Seed service becomes obsolete; demo prep simplifies.
- Closer to a deployable shape — what a real CIB7 user would run.
- Unblocks longer-running demos and integration tests against persistent state.

**Cons:**
- Adds a container, a volume, and a datasource config block.
- Migration story for the realm-export and seed data needs more thought.
- POC-flavoured behavior (wipe on restart) is sometimes useful for clean demos.

**Context:** The current H2 setup is documented in `docs/architecture.md § Data persistence` and `docs/cib7.md § Engine configuration (application.yaml)`. The Camunda 7 starter (and CIB seven 2.1) auto-configures H2 when no `spring.datasource` is set; swapping is the standard "add a real datasource" Spring Boot pattern. The CIB seven 2.1 schema is documented in their reference; `camunda.bpm.database.schema-update: true` already handles first-start migrations. The MCP design (`~/.gstack/projects/cib7-react-poc/kriks-main-design-20260604-102459.md`) explicitly flags the H2 limitation in P4 and Open Question 4.

**Depends on / blocked by:** Nothing structural. Most cleanly tackled after the MCP POC ships and demo-gate-2 has been verified once, so the seed-script approach is fully proven before being deleted.
