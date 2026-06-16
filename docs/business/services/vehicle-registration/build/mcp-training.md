# vehicleRegistration — guidance for the LLM

This service registers an applicant's personal details and runs them through an
auto-approval decision based on age and product price. It's the simpler of the
services in this deployment; businessRegistration (Estonia OÜ creation) builds
on the same pattern.

## What to ask the user for

The owner's **first name, last name, and email are filled automatically from
their signed-in account** — do NOT ask for them or send them; the engine sets
them at start and re-validates them on completion. You still need:

- **age** — applicant's age in years (integer, 0–130)
- **objectId** — a product ID from the public catalogue at
  https://api.restful-api.dev/objects

If the user doesn't know an objectId, you can fetch a few options from the
catalogue and present them. Prices vary; the choice affects whether the
application auto-approves or routes to manual review.

Do NOT include `initiator` in your start_process call. The engine writes that
from the authenticated Keycloak user automatically.

## ID document — required before completing the first task

After `start_process` lands, the engine creates a **Submit personal details**
user task. That task requires the applicant to attach a copy of their
national ID card or passport (PDF, JPEG, or PNG, ≤10 MB). The flow is:

1. Ask the user to attach a copy of their ID document to the chat.
2. Call `upload_document` with:
   - `category: "applicant-id-document"`
   - `filename`, `contentType`, `base64` from the attachment.
3. The tool returns `{ pendingKey, filename, contentType }`. Keep that object.
4. Call `complete_task` with the task id and variables including
   `pendingIdDocument` set to the object you just got back. That's the value
   of the task's `requiredDocuments[0].writeTo` — `get_form_schema` lists it
   explicitly.

If you skip the upload and try to complete the task without
`pendingIdDocument`, the MCP server short-circuits with a `DOCUMENT_REQUIRED`
error pointing you back here — there is no way around the document.

## Auto-approval rule

Once the case clears the personal-details task, the auto-approval DMN evaluates:

- age ≥ 18 AND price < 100 → autoDecision = "approve" → process ends, applicant
  notified by email.
- Anything else → autoDecision = "review" → civil-servant reviews the
  application in the back-office UI, then accepts or sends back with a reason.

The price comes from the catalogue lookup the engine performs after start, so
you cannot predict approval status from the start_process input alone. Tell the
user the application is being processed and check status with list_my_processes
when they ask.

## Co-owners are portal-only

The applicant form supports listing additional co-owners that have to sign off
before the case reaches the back office. The sign-off loop relies on emailed
links and a Camunda message correlation that MCP does not surface yet. If a
user wants to use that flow, point them at the web portal at
http://localhost:3000. Plain solo applications work entirely via MCP.

## Name and email come from the signed-in account

The owner's name and email are no longer form fields you fill — the engine reads
them from the authenticated Keycloak user at process start, writes them as
process variables, and re-validates them when the applicant task is completed.
Do not collect them and do not pass them to `start_process` / `complete_task`;
they are rejected by the schema, and a forged value would be refused with an
HTTP 400. Because the email is now always known, a **manually-reviewed**
registration runs the state-fee invoice + approval email and then waits at the
payment step (`/pay/{processInstanceId}`), which has no MCP affordance — those
cases complete up to payment and the user pays on the web. Auto-approved cases
(adult owner, low-value older vehicle) skip review and payment and finish over
chat.

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
