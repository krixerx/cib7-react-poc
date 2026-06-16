# businessRegistration — guidance for the LLM

This service registers a new Estonian private limited company (OÜ). It's
the headliner of the spec-first × MCP demo: one markdown spec produces the
BPMN, React forms, DMN, this manifest, and this training markdown — and
Claude Desktop can drive the entire flow without the applicant opening a
portal.

## What to ask the user for

To start a businessRegistration, you need these pieces of information
(name and email are added automatically — see the note below):

- **companyName** — the trade name. Must end in "OÜ" (limited liability).
  If the user gives "Acme" without the suffix, ask whether to append it
  before calling start_process — the form rejects names without it and an
  Ajv error is more annoying than one clarifying question.
- **boardMembers** — list of board members, each with first name, last
  name, and 11-digit Estonian personal code (isikukood). Minimum one.
- **shareCapital** — share capital in EUR. Must be at least 2500 (historical
  Estonian Commercial Code minimum). Below that, ask whether the user
  meant a different company form (sole proprietorship, MTÜ non-profit).
- **applicantAge** — the applicant's age in years. Call
  query_user_history('applicantAge') before asking and pre-fill if found.

The applicant's **first name, last name, and email are filled automatically
from their signed-in account** — do NOT ask for them or send them. The engine
writes them at process start and re-validates them on completion; a forged
value is refused with an HTTP 400.

Do NOT include `initiator` in your start_process call. The engine sets it
from the authenticated Keycloak user automatically.

## MCP-path scope (what chat can and cannot do)

The chat path registers a **sole-founder OÜ only**, and two web-portal
capabilities are deliberately not reachable over MCP. Tell the user up front
when their request needs one of these — don't let them discover it after
starting the process:

- **Co-founder e-signatures** — the multi-founder flow keys off an
  `additionalFounders` variable and drives per-founder signing via public
  tracking links the engine emails out. MCP cannot send `additionalFounders`
  (the start schema is closed) and has no tool to correlate the signature
  receive task, so every chat-started registration is a single-founder OÜ.
- **Articles of Association upload** — the AoA document upload has no field in
  the MCP manifest, so no AoA is attached on the chat path.

One more thing to set expectations on: because the applicant's email is now
always known (filled from their account), an approved registration generates a
state-fee invoice + approval email and then waits at a payment step
(`/pay/{processInstanceId}`). There is no MCP payment tool, so over chat the
case completes up to that payment step and the user pays on the web. Everything
before payment — sole-founder registration, auto-approval, civil-servant
review, the send-back loop — works end-to-end over chat.

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
applicant task directly via MCP — call complete_task with the same
variables (or corrected ones if the user wants to change); name and email are
already on the instance and must not be resent. Once completed,
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
