---
name: process-testing
description: "How a service and a pack are tested here: service examples in the spec, flow scenarios, DMN examples, tests tagged `pack`, scripts/pack-check.sh, and the Playwright suite in e2e/. Use before adding or changing a service example or a pack test, when pack-check fails, and when deciding what proves a service works."
---

# Testing services and packs

Tests that hold for any pack are tagged `pack`; `scripts/pack-check.sh [pack-dir]` runs exactly those
(engine and backend through Maven with `-Dgroups=pack`, plus `src/pack` in the frontend and
`pack.test.ts` in `mcp/`). It does not run the mobile tests. A pack's CI runs the same script, so a
test tagged `pack` must not name a service of the test pack.

## Service examples are tests

Examples in the spec run as tests, written from the policy and never read off generated output
(`docs/platform-api.md` holds the format):

| Spec section | Test |
|---|---|
| `decisions/<id>.md` `## Examples` | `DecisionExamplesTest` |
| README `### Fee examples` | `FeeExamplesTest` |
| `forms/<id>.md` `## Submission examples` | `SubmissionExamplesTest` |
| `forms/<id>.md` `## Behaviour examples` | `frontend/src/pack/behaviour.test.ts` |
| `service-tasks/<task>.md` `## Example` | `TemplateExamplesTest` (adds a hostile suffix) |
| README `## Flow scenarios` | `FlowScenariosTest` |

Each is required where its section exists; flow scenarios are recommended.

- **Decisions:** the test runs the pack's DMN directly, and each row must match exactly one rule. `## Examples without <rule>` removes a demo rule first.
- **Flow scenarios:** steps are `expectTask`, `complete`, `expectVariables`, `expectWaitingAt`. The job executor is off, so a case stops at its first async step and no connector calls out. A flow scenario proves routing; a template example proves the integration payload.
- **`ServiceSpecsTest`** ties specs to the BPMN: one spec per service task naming its `**BPMN task id:**`, no stale specs, the spec's template block equal to the template file, and a `decisions/<id>.md` for every `decisionRef`.

## End to end

`e2e/` is Playwright and targets the public VM by default (`https://companylab.ai`); `E2E_BASE_URL`
overrides it. Set `E2E_BASE_URL` to a local stack before you write anything that starts a case,
because such a test leaves data behind on the shared VM. Today's tests only log in and navigate.
No CI job runs `e2e/`.
