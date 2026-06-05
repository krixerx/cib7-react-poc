# personRegistration — guidance for the LLM

This service registers an applicant's personal details and runs them through an
auto-approval decision based on age and product price. It's the simpler of the
services in this deployment; businessRegistration (Estonia OÜ creation) builds
on the same pattern.

## What to ask the user for

To start a personRegistration, you need exactly four pieces of information:

- **firstName** — applicant's first name
- **lastName** — applicant's last name
- **age** — applicant's age in years (integer, 0–130)
- **objectId** — a product ID from the public catalogue at
  https://api.restful-api.dev/objects

If the user doesn't know an objectId, you can fetch a few options from the
catalogue and present them. Prices vary; the choice affects whether the
application auto-approves or routes to manual review.

Do NOT include `initiator` in your start_process call. The engine writes that
from the authenticated Keycloak user automatically.

## Auto-approval rule

The auto-approval DMN evaluates:

- age ≥ 18 AND price < 100 → autoDecision = "approve" → process ends, applicant
  notified by email.
- Anything else → autoDecision = "review" → civil-servant reviews the
  application in the back-office UI, then accepts or sends back with a reason.

The price comes from the catalogue lookup the engine performs after start, so
you cannot predict approval status from the start_process input alone. Tell the
user the application is being processed and check status with list_my_processes
when they ask.

## After start_process

The engine creates a "Submit personal details" user task assigned to the
applicant (the authenticated user). The variables you passed at start time are
pre-filled on the task.

In the current POC, the applicant confirms the pre-filled form via the React
portal at http://localhost:3000. Once complete_task is wired into MCP (eng-
review task T9), you'll be able to finish the task directly without sending
the user to the portal.

If the civil servant sends the case back with a reason, the applicant returns
to the same task to correct and resubmit. The send-back reason is available
via query_user_history (once that tool lands).

## Status interpretation

When the user asks "is my application approved?":

- Process instance `running`, no user tasks open → between activities (rare;
  retry the check in a few seconds).
- Process instance `running`, "Submit personal details" task open → applicant
  hasn't confirmed yet.
- Process instance `running`, no tasks open, no incidents → in transit through
  a service task (price lookup, DMN evaluation, or email).
- Process instance `running`, "Review application" task open → with civil
  servant; typical 1–2 business days in production, but in this POC the
  reviewer (Homer) acts immediately.
- Process instance `completed` → either auto-approved or accepted by civil
  servant; check history for the final `autoDecision` and `decision` variables.

## Common applicant questions

- "Can I register multiple people at once?" — No, start one process per person.
  The engine does not deduplicate; the applicant may legitimately register
  several family members in sequence.
- "What if my objectId is wrong?" — The price lookup will fail and create an
  incident visible in Cockpit. The applicant will need to restart.
- "Can I undo?" — No undo; the applicant can request to send the case back
  during civil-servant review, but once `autoDecision = approve` the process
  ends and a new application is required.
