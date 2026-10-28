---
name: backend-conventions
description: Rules for writing, changing or reviewing Java code in cib7/ (engine and plugins) and backend/ (business API): identity, ownership, untrusted variables, error handling and logging, registry integrations, write races, configuration, and Spring Boot 4 tests. Use before changing a controller, filter, security config, entity, migration, delegate, application*.yaml or a Java test, and when running /review.
---

# Backend conventions

The rule table for Java changes in `cib7/` and `backend/`. Where a rule is already written down in
`CLAUDE.md` or `docs/security.md`, the table points there and this file does not repeat it; read the
pointed section when your change touches the rule. Rules with a Details section below are lessons
from a sibling project (roppoc) and the team's harid backend practices that this repository had not
recorded yet.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule | Where |
|---|---|---|---|
| B1 | BLOCKER P1 | No wildcard engine grants for applicants | `docs/security.md` §1 |
| B2 | BLOCKER P1 | Client-written variables pass `variable-policy.json` and the form schema; system variables are server-side only | `docs/security.md` §2 |
| B3 | BLOCKER P1 | Every id or key a caller passes is checked against the caller; unknown and forbidden both answer 404 | `docs/security.md` §6 |
| B4 | BLOCKER P1 | Every `/api` path is public, internal or JWT; `SecurityConfig` ends deny-all | `docs/security.md` §5 |
| B5 | BLOCKER P1 | Capability links minted server-side, bound and never returned to other parties | `docs/security.md` §3 |
| B6 | BLOCKER P1 | Identify a user by the username claim, never `Authentication.getName()` | [B6](#b6) |
| B7 | BLOCKER P1 | The caller's scope goes into the query, never into a filter on its answer | [B7](#b7) |
| B8 | BLOCKER P1 | No check-then-act on writes | [B8](#b8) |
| B9 | BLOCKER P1 | Every security fix lands with a negative test | `docs/security.md` §13 |
| B10 | BLOCKER P2 | An entity change ships a Flyway migration; a CIB seven bump ships an engine migration | `CLAUDE.md` gotchas |
| B11 | BLOCKER P2 | Variables over ~4 kB are `byte[]` | `CLAUDE.md` gotchas |
| B12 | BLOCKER P2 | A registry or integration call keeps "found", "not found" and "failed" apart; an absent record derives nothing | [B12](#b12) |
| B13 | BLOCKER P2 | Every endpoint has authorization tests: allowed role, wrong role, another person's object, no token | [B13](#b13) |
| B14 | BLOCKER P2 | A refusal test asserts status and message; an absence is asserted only after asserting success | [B14](#b14) |
| B15 | BLOCKER P2 | A test pins every property it asserts on | [B15](#b15) |
| B16 | BLOCKER P2 | Context tests pass with no Docker and no network | [B16](#b16) |
| B17 | BLOCKER P2 | Jackson 3 in `backend/`; Jackson 2 in `cib7/` only where Spin or the engine needs it | [B17](#b17) |
| B18 | WARNING | A request that fails leaves a log line | [B18](#b18) |
| B19 | WARNING | A lookup before a form is advisory; the process decides | [B19](#b19) |
| B20 | BLOCKER P3 | Fixture dates that a validity period is measured against are relative | [B20](#b20) |
| B21 | BLOCKER P3 | `MdcUserFilter` stays after Spring Security; a new profile lists `graylog` beside it | `CLAUDE.md` gotchas |

## Details

### B6

**BLOCKER P1.** Identify a user by the username claim, never `Authentication.getName()`.

With a Keycloak JWT, `Authentication.getName()` is the `sub` claim, a UUID. Anything that stores or
compares an identity (case starter, storage prefix `pending/<user>/`, MDC `user_id`) reads the
configured username claim. `MdcUserFilter` does this and falls back to `getName()` only for
client-credentials tokens, where the client id is the best answer; keep that shape. `@WithMockUser`
hides the difference, because there `getName()` *is* the username, so identity code is tested with a
real JWT whose `sub` differs from the username.

*From roppoc.*

### B7

**BLOCKER P1.** The caller's scope goes into the query, never into a filter on its answer.

When a user may only see their own cases, files or tasks, the restriction is part of the engine query
(`startedBy`, `taskAssignee`, `processInstanceIds`) or the SQL, not a `stream().filter(...)` over
everything. Filtering afterwards loads every user's data into a response meant for one, and its cost
grows with the whole deployment instead of with the answer.

*From roppoc.*

### B8

**BLOCKER P1.** No check-then-act on writes.

A query that checks existence or state followed by a separate write can be invalidated between the
two. Use the affected row count of a conditional update or delete, a unique constraint, `@Version`
optimistic locking (the backend already has two), or a row lock. Pre-checks are fine for reads.

**Detect:** `existsBy...`/`findBy...` followed by `save`/`delete` on the same entity with no
constraint, version or lock behind it.

*From the harid backend practices, adapted to JPA.*

### B12

**BLOCKER P2.** Keep "found", "not found" and "failed" apart; an absent record derives nothing.

A call to a registry or an outside system has at least three outcomes, and collapsing a timeout into
"not found" tells a citizen their input is wrong during an outage. When the record is absent, derived
fields stay `null`; a default (`false`, `0`) is a statement about a person nobody could find, and a
decision table may refuse or approve them on it.

*From roppoc.*

### B13

**BLOCKER P2.** Every endpoint has authorization tests.

For each endpoint: each allowed role (applicant, civil servant, `statistics-viewer` where relevant),
a role that is not allowed (403), an allowed role reaching **another person's** object (404, B3), and
no token (401). Capability endpoints add an expired, wrong-party and wrong-round token. This is the
concrete shape of `docs/security.md` §13 for endpoints.

*From the harid integration-test practices.*

### B14

**BLOCKER P2.** A refusal test asserts status and message; an absence is asserted only after success.

- For a 4xx, assert the status **and** part of the body, so the test proves it hit the refusal it was
  written for.
- Assert success before asserting that a body does **not** contain something. A "does not contain the
  officer's name" check passes against a 404 while the leak is live; this happened in roppoc.
- Compare statuses with `HttpStatus` constants; use `containsExactly` when the expected set is known.

*From harid and roppoc.*

### B15

**BLOCKER P2.** A test pins every property it asserts on.

An exported OS environment variable outranks every `application*.yaml`, including
`application-test.yaml`. A developer shell with a deploy value exported (`INTERNAL_TOKEN`,
`BUS_TOKEN`, client secrets) arms the app under test with it while the test still presents the
literal, and the failure points at the security chain. Set asserted values in
`@SpringBootTest(properties = ...)` or `@TestPropertySource`, which the shell cannot beat. Never add a
`src/test/resources/application.yaml`: it replaces the main file instead of layering onto it.

*From roppoc.*

### B16

**BLOCKER P2.** Context tests pass with no Docker and no network.

CI runners have neither the compose stack nor spare cores. A test that boots the full context and
dials Keycloak, the ESB or Postgres at startup passes on a developer machine with the stack up and
fails on the runner. Disable such clients through test properties, or use Testcontainers in a test
that skips without Docker.

*From roppoc.*

### B17

**BLOCKER P2.** Jackson 3 in `backend/`; Jackson 2 in `cib7/` only where Spin or the engine needs it.

Spring Boot 4 configures Jackson 3 (`tools.jackson.databind.ObjectMapper`); its read failures are
unchecked. `backend/` uses it throughout. `cib7/` manages both BOMs because the CIB seven Spin JSON
dataformat is built on Jackson 2, so its policy and consent code uses `com.fasterxml.jackson`. A new
class in `cib7/` uses Jackson 2 only when it shares objects with Spin or the engine; otherwise use the
one Spring configured. Boot 4 tests use `MockMvcTester` or `RestTestClient` (no `TestRestTemplate`)
and `@MockitoBean`.

*From roppoc, adjusted to this repository's two modules.*

### B18

**WARNING.** A request that fails leaves a log line.

`backend/` has no `@RestControllerAdvice`: a `ResponseStatusException` becomes Spring's default
error body, and 4xx refusals are not logged. When adding a refusal that matters for operations or
security (ownership refusals, capability-link failures, policy rejections), log it at WARN with the
reason, without the token or other secrets. A failure that leaves no log costs more to find than the
bug costs to fix.

*From roppoc.*

### B19

**WARNING.** A lookup before a form is advisory; the process decides.

A lookup that prefills a form may warn but must not block submission. The BPMN/DMN that judges the
submission decides, and it must handle the "not found" branch. A gate in the browser moves a decision
out of the model and makes that branch unreachable.

*From roppoc.*

### B20

**BLOCKER P3.** Fixture dates are relative.

Test and seed data that a validity period is measured against (document expiry, consent round
expiry, payment deadlines) use offsets from today, not calendar dates, or the happy path starts failing
on a schedule.

*From roppoc.*
