# Core test pack

The service pack the core's own tests run on: a frozen copy of the
reference pack (`packs/reference/`, pack version 0.14.0) taken on
2026-10-07, before the reference pack moved to its own repository. The
core's test suites, `mvn spring-boot:run` and `npm run dev` default to it.

It is a fixture, not a product: nothing here is deployed, and it changes
only when a core change needs it to (a platform API change, a new core
feature a test must exercise). Keep it a valid pack:
`scripts/pack-check.sh packs/test` must pass. The reference services'
names in core tests are sample data from this copy.
