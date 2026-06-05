# businessRegistration — guidance for the LLM

This service registers a new Estonian private limited company (OÜ). It's
the headliner of the spec-first × MCP demo: one markdown spec produces the
BPMN, React forms, DMN, this manifest, and this training markdown — and
Claude Desktop can drive the entire flow without the applicant opening a
portal.

## What to ask the user for

To start a businessRegistration, you need six pieces of information:

- **companyName** — the trade name. Must end in "OÜ" (limited liability).
  If the user gives "Acme" without the suffix, ask whether to append it
  before calling start_process — the form rejects names without it and an
  Ajv error is more annoying than one clarifying question.
- **boardMembers** — list of board members, each with first name, last
  name, and 11-digit Estonian personal code (isikukood). Minimum one.
- **shareCapital** — share capital in EUR. Must be at least 2500 (historical
  Estonian Commercial Code minimum). Below that, ask whether the user
  meant a different company form (sole proprietorship, MTÜ non-profit).
- **applicantFirstName**, **applicantLastName**, **applicantAge** — the
  applicant's own identity. **Always call query_user_history on each of
  these BEFORE asking the user.** If history exists, pre-fill all three
  and confirm in a single message ("I've got your name as Bart Simpson,
  age 30 — confirm?") rather than re-prompting field by field.

Do NOT include `initiator` in your start_process call. The engine sets it
from the authenticated Keycloak user automatically.

## Auto-approval rule

The business-auto-approval DMN evaluates `applicantAge` and `shareCapital`
with FIRST hit policy:

- `shareCapital < 2500` → autoDecision = "review" (manual review)
- `applicantAge < 18` → autoDecision = "review"
- `applicantAge >= 18 AND shareCapital >= 2500` → autoDecision = "approve"

Auto-approved cases skip the civil-servant queue and end immediately with
an approval email. Reviewed cases route to a civil servant who can accept
or send back with a reason.

You can predict the auto-approval outcome from the start_process input
alone (no service-task lookup like personRegistration's price), so you can
tell the user "this should be auto-approved" or "this will need civil-
servant review" up front.

## After start_process

The engine creates the "Submit business details" user task assigned to the
applicant. The variables you passed at start time are pre-filled.

Since complete_task is already wired (eng-review T9), you can finish the
applicant task directly via MCP — call complete_task with the same six
variables (or corrected ones if the user wants to change). Once completed,
the engine runs the DMN, then either auto-approves (process ends, approval
email sent) or routes to civil-servant review.

If the case is sent back with a reason, the applicant returns to the same
business-details task with `sendBackReason` set. The MCP agent should:

1. Detect via query_user_history('sendBackReason') that a previous attempt
   was sent back.
2. Surface the reason to the user.
3. Offer to correct the previous submission rather than starting from
   scratch.

## Status interpretation

Use list_my_processes to check status. Map the engine state to user-facing
language:

- Process instance `running`, "Submit business details" task open →
  applicant hasn't confirmed yet (or sent back and awaiting correction).
- Process instance `running`, no user tasks open →
  in transit through the DMN or a service task. Retry in a few seconds.
- Process instance `running`, "Review business registration" task open →
  with civil servant. Typical 1-2 business days in production; in this
  POC the reviewer (Homer) usually acts immediately.
- Process instance `completed` → either auto-approved or accepted by
  civil servant. Check history for the final `autoDecision` and `decision`
  variables to tell which path it took.

## Common applicant questions

- **"Can I register multiple OÜs at once?"** — No, one process per OÜ.
  The engine does not deduplicate; the applicant may legitimately register
  several companies in sequence.
- **"What if I don't have a personal code for one of the board members?"**
  — A real registration requires it (Estonian state ID). For non-residents
  there's a separate process (e-Residency or alien personal code) that
  the POC doesn't cover; ask the user to obtain one first.
- **"Can I change the share capital after registration?"** — Not via
  this service. Production would expose a separate `capitalChange` service.
- **"My case was sent back, can I see the reason?"** — Yes, call
  query_user_history('sendBackReason') for the user's running instance —
  the reviewer's reason is stored as a process variable and survives
  history queries.
