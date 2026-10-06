# TODOS

Captured during plan reviews. Items here are deferred work the team agreed to revisit, with enough context that anyone picking them up understands the motivation and the current state.

---

## T1 — Replace in-memory H2 with PostgreSQL + volume (done)

Done on 2026-10-06 (checkpoint 3 of the core/pack split): one `postgres`
container with a database and role each for `cib7` and `backend`, Flyway for
both schemas (the engine's through the CIB seven jar's own scripts), in-memory
H2 kept for tests. See `docs/architecture.md`, Data persistence.
