# Redomain plan: making both POC flows recognizable Estonian e-gov services

**Status:** proposal (2026-06-10) · **Author:** Erki + Claude · **Scope:** `personRegistration` and `businessRegistration` BPMNs, their DMNs, forms, emails, and PDF templates.

## Why

The POC has two working BPMN flows that exercise every CIB seven feature we wanted to try: a multi-instance subprocess with message correlation, a DMN business rule, civil-servant review with a non-interrupting timer, ID-document upload to RustFS, two generated PDFs via Gotenberg. **The plumbing works. The story doesn't.**

- `personRegistration` is named "Person Registration" but the applicant form asks them to *pick a product from a restful-api.dev catalog*, fetches a *product price*, and the DMN routes on `age + price`. Anyone watching the demo asks "what is this service for?" — and the honest answer is "nothing real."
- `businessRegistration` is the opposite: the name and domain are honest (Estonian OÜ), but the flow is bare — submit → DMN → review → email. No documents, no co-founder sign-off, no fee, no certificate. The technology depth of the person flow isn't visible here.

Goal: reframe both as recognizable Estonian e-government services without throwing away the plumbing. Anyone seeing the demo should immediately see a service they (or their accountant, or their neighbour) actually need.

## End-state vision

Two parallel e-government registration services, each ending with an official PDF the applicant can save:

| | Citizen-facing | Business-facing |
|---|---|---|
| Service | **Vehicle Registration & Title Transfer** (Transpordiamet) | **Estonian OÜ Registration** (Ariregister) |
| Initiator | Vehicle owner | Future board member |
| Multi-party sign-off | Vehicle co-owners | OÜ co-founders / board members |
| Document upload | Owner ID / power of attorney | Articles of Association |
| External lookup | Vehicle registry value | (Optional) PEP/sanctions screen |
| Auto-approval inputs | Owner age, vehicle value, vehicle age | Founder age, share capital, founder residency |
| Review role | Transport Authority officer | Business Register officer |
| Output PDFs | State fee invoice + Vehicle Registration Certificate | State fee invoice + B-card extract |

The two flows mirror each other intentionally — same patterns, different domain — so the demo lands as "we built a reusable platform, here are two services."

---

## Part 1 — `personRegistration` → Vehicle Registration & Title Transfer

The existing flow shape fits almost verbatim. Most of the work is rename + small additions.

### Naming map (rename-only)

| Today | Becomes | Where |
|---|---|---|
| Process name "Person Registration" | "Vehicle Registration" | `<bpmn:process name="…">`, SPA "Start a process" card |
| `Task_SubmitDetails` "Submit personal details" | "Submit owner & vehicle details" | BPMN `name`, form heading |
| `objectId` (product) | `vin` (Vehicle Identification Number) | form, BPMN variable doc, `Task_GetPrice` URL param |
| Product list from `restful-api.dev` | Internal **Vehicle Registry** endpoint `GET /api/vehicle-registry/{vin}` returning `{make, model, year, value, fuelType, registrationStatus}` for a curated catalog (VW Golf, Skoda Octavia, Tesla Model 3, …). Unknown VIN → 404 → existing `9999` JUEL fallback → review. See **D4**. | `frontend/src/api/objectsApi.ts` → `vehicleRegistryApi.ts`; new `VehicleRegistryController` in `backend` (or `cib7`); `Task_GetPrice` → `Task_LookupVehicle` |
| `price` (variable) | `vehicleValue` | BPMN, DMN input, PDF templates |
| `additionalOwners` | `coOwners` | BPMN, form, DB column name in `OwnerConfirmationController` payloads |
| `Task_SendApplicantTrackingEmail` | "Send owner tracking email" | BPMN `name`, email copy |
| `Task_Review` "Review application" | "Transport Authority review" | BPMN `name`, candidateGroup label in Keycloak (group stays `civil-servant` to avoid the [cibseven-keycloak-group-id-strips-slash](../../.claude/projects/.../memory) headache) |
| `Task_GeneratePdf` / "Generate approval PDF" + `Task_StoreApprovalPdf` | "Generate state fee invoice" + "Store fee invoice" | task names, template `approval-pdf.json.ftl` → `state-fee-invoice.html.ftl` |
| `Task_GenerateCertificatePdf` / "Generate certificate PDF" | "Generate Vehicle Registration Certificate" | template `certificate-pdf.json.ftl` → `vehicle-registration-certificate.html.ftl` |
| `EndEvent_Approved` "Application approved" | "Vehicle registered" | end event label |
| DMN rule IDs `Rule_Minor`, `Rule_AutoApprove`, `Rule_DefaultReview` | `Rule_UnderageOwner`, `Rule_LowValueAdultOwner`, `Rule_HighValueReview` | reads like business policy in the modeller |

### Form changes (`PersonalDetailsForm.tsx` → `OwnerVehicleForm.tsx`)

Keep the existing layout; rename fields and add two:

- **Owner identity** — firstName, lastName, age, applicantEmail (unchanged)
- **ID document** — relabel "ID card or passport" → "Owner ID document" (unchanged behavior)
- **Vehicle selection** — relabel "Product" → "Vehicle (from registry)"; show `make · model · year · €value` from the new registry endpoint (see **D4**)
- **`vehicleAgeYears`** — *not* a form input. Derived in `Task_LookupVehicle` from `year` returned by the registry (`vehicleAgeYears = currentYear - vehicle.year`), so the applicant doesn't re-enter data the registry already knows.
- **Co-owners** — relabel "Additional owners" → "Vehicle co-owners (must sign)"

### Targeted extensions (the new value)

1. **State fee payment step** between approval and certificate generation. See **D2** for the UI shape decision.
   - New `bpmn:message` `PaymentReceived`.
   - New `bpmn:receiveTask` `Task_WaitForPayment` after `Task_StoreApprovalPdf` (the invoice now means "please pay") and before `Gateway_BeforeCertificate`.
   - New REST endpoint `POST /api/payments/{piId}/confirm` in `cib7` that correlates the message — modelled on `OwnerConfirmationController`.
   - New `/pay/{piId}` page in the SPA: shows the invoice PDF inline + a fake **Estonian SEPA payment** panel (recipient IBAN, reference number, amount in EUR, sender-bank dropdown — Swedbank / SEB / LHV / Coop) + a single "Confirm payment" CTA that POSTs to the correlation endpoint. No real PSP integration — but visually believable, not a raw button.
2. **`vehicleAgeYears` as a third DMN input** in `auto-approval.dmn`:
   - Rule: applicantAge < 18 → review
   - Rule: vehicleValue < 5000 AND vehicleAgeYears >= 10 AND applicantAge >= 18 → approve (cheap old car, adult owner)
   - Rule: vehicleValue >= 50000 → review (luxury — always reviewed)
   - Default → review
3. **Email copy** — every email's Subject/Text gets vehicle-context wording. Specifically:
   - Tracking email: "Sign off on registering [Make Model] in your name"
   - Owner confirmation email: same
   - Reminder email: "Vehicle registration application waiting for review"
   - Send-back email: "Your vehicle registration needs corrections"
   - Approval email: "Your Vehicle Registration Certificate is ready" — attach the new certificate PDF
4. **PDF templates** — rewrite the bodies (HTML in `templates/*.html.ftl`) to look like real Transpordiamet output. Keep the JSON wrapper exactly as it is (don't break the `{html, filename}` contract with `pdf-renderer`).

### What stays unchanged

- All BPMN element IDs (`Task_SubmitDetails`, gateways, sequence flows). Renaming `name=` is enough; ID changes would force history rewriting and DMN deployment cascades.
- The `pendingIdDocument` → `Task_AttachIdDocument` migration path.
- The non-interrupting `R/PT2M` reminder boundary timer.
- The `Gateway_HasPendingUpload` and `Gateway_SendApprovalEmail` JUEL guards (the [defensive `hasVariable()` pattern](memory) we already discovered).
- The message correlation contract for `OwnerConfirmation` and `SendToProcess`.

---

## Part 2 — `businessRegistration` → Estonian OÜ Registration (polish + extensions)

The domain is already right. The polish work is small; the extensions are where this flow earns its keep.

### Polish (rename + copy)

| Today | Becomes |
|---|---|
| `Task_SubmitBusinessDetails` "Submit business details" | "Submit OÜ founding details" |
| `Task_ReviewBusinessRegistration` "Review business registration" | "Business Register review" |
| DMN rule IDs `Rule_LowCapital`, `Rule_Minor`, `Rule_AutoApprove` | `Rule_BelowMinimumCapital`, `Rule_UnderageFounder`, `Rule_AdultFounderSufficientCapital` |
| `business-approval-email.json.ftl` | Body rewritten to read like a real Ariregister confirmation: include reg-code (synthesised `1[0-9]{7}`), date, EMTAK code |
| End event "Registration approved" | "OÜ entered in Business Register" |

### Targeted extensions (the meat)

These directly port the patterns that already work in personRegistration:

1. **Co-founder digital signing block** — multi-instance subprocess identical in shape to `SubProcess_OwnerConfirmations`:
   - Iterate over `boardMembers` collection.
   - For each: send signing-link email + park on `receiveTask` waiting for message `FounderSignature`.
   - Completion condition: `${rejectedByFounder == true}` short-circuits.
   - New controller `FounderSignatureController` mirrors `OwnerConfirmationController` line-for-line.
   - After the subprocess: synchronisation receive task on message `SubmitToRegister` (mirrors `Task_WaitSendToProcess`).
2. **Articles of Association upload** — applicant attaches `articles.pdf`; we reuse the entire `pendingIdDocument` → `Task_AttachIdDocument` pattern with `pendingAoaDocument` and `Task_AttachArticlesOfAssociation`. Internal endpoint, X-Internal-Token, RustFS path `process/{piId}/articles.pdf`.
3. **State fee payment step** — same `PaymentReceived` receive task added between approval and certificate generation (the same payment endpoint can serve both processes — keyed by process instance id).
4. **DMN inputs extended** — add `applicantResidency` (`citizen` / `e-resident` / `foreign`):
   - Rule: shareCapital < 2500 → review (existing)
   - Rule: applicantAge < 18 → review (existing)
   - Rule: applicantResidency = "foreign" → review (new — foreign founders always reviewed)
   - Rule: applicantResidency in {"citizen", "e-resident"} AND adult AND shareCapital >= 2500 → approve
5. **Output PDFs** — port both PDF tasks from personReg:
   - **State fee invoice** (€265 fast-track or €200 standard)
   - **B-card extract** (the actual business register output) — synthesised registration code, company name, board, share capital, EMTAK activity code, date

> **VAT registration sub-process is deferred** — see **D3**. Mentioned as future work in the OÜ service README after rename PRs land.

### Form changes (`BusinessDetailsForm.tsx`)

- Existing: `companyName`, `boardMembers`, `shareCapital`, applicant identity
- Add: `applicantResidency` (radio: citizen / e-resident / foreign)
- Add: Articles of Association upload (file input, mirror of ID document upload)
- Existing validation `companyName.endsWith("OÜ")` stays; add validation that all `boardMembers[].personalCode` look like Estonian personal codes (11 digits, century+gender first digit) — pure regex, no external check

---

## Cross-cutting work

1. **SPA "Start a process" cards** in `frontend/src/pages/Home`: rewrite the descriptions and CTAs to sell the service value. Add an SVG icon per service.
2. **Memory file updates** — add a new entry pointing at this plan; mark the old "person registration uses product picker" mental model as obsolete.
3. **Service spec READMEs** — `docs/business/services/person-registration/README.md` and `…/business-registration/README.md` need to be updated *after* implementation (the bpmn-to-mermaid script will regenerate the diagrams; the prose around it needs rewriting). Plan: do the rename PRs first, run the script, then rewrite the prose.
4. **Folder rename** — should `docs/business/services/person-registration/` become `…/vehicle-registration/`? Decided: **yes**, but in the last commit of the work so intermediate PRs don't churn paths. Mirror move in `frontend/src/forms/personal-details/` → `…/owner-vehicle/`.
5. **Process key rename** — `personRegistration` → `vehicleRegistration` in the BPMN. **Decided yes per D1.** Done in the same PR as the folder rename (final PR #8) with `docker compose down -v` to wipe H2. Touch points to grep: `cib7/**/*.{java,bpmn}`, `frontend/src/api/*.ts`, `frontend/src/pages/**/*.tsx`, `mcp/src/**`, `scripts/seed-history.*`.

---

## Implementation order

Each step is independently shippable / demoable. Stop after any step and the demo still tells a coherent story.

1. **Polish-only rename** — both processes: rename BPMN `name=`, DMN rule IDs, task labels, email subjects/bodies, PDF template content. Update SPA copy. **Visible result:** demo now shows "Vehicle Registration" and "Estonian OÜ Registration" everywhere; no behavior change.
2. **personReg DMN extension** — add `vehicleAgeYears` to form + BPMN + DMN. Update PDF templates to read like Transpordiamet output. **Visible result:** the auto-approval decision table reads like real Transport Authority policy.
3. **businessReg co-founder signing block** — port the multi-instance subprocess and controller. **Visible result:** business flow now has the same demo "wow" (multi-party emails + tokenised public confirmation page) as the person flow.
4. **businessReg Articles of Association upload** — port the `pendingIdDocument` pattern. **Visible result:** business flow now has document handling.
5. **businessReg two PDFs** — port the fee-invoice + certificate pattern. **Visible result:** business flow ends with downloadable B-card extract.
6. **businessReg DMN extension** — add `applicantResidency`. **Visible result:** decision table reads like Business Register policy.
7. **State fee payment step** — applied to both flows in the same PR (the receive task + endpoint + SPA page is shared infra). **Visible result:** the flow now demonstrates a synchronous "pause for external event" beyond owner confirmation.
8. **Folder + process-key renames** (history-breaking). **Visible result:** repo structure matches the domain.

Total: 8 PRs, each demoable, ordered so the highest-value work (polish + business-flow parity) ships first.

---

## Resolved decisions

### D1 — Rename the process key `personRegistration` → `vehicleRegistration`

**Decision:** rename, in the same PR as the folder rename (final step #8). Wipe H2 (`docker compose down -v`) as part of that PR.

**Why:** the process key is visible in five places that touch every demo viewer — cibseven-modeller's deployments list, Cockpit, the `/engine-rest/process-definition/key/…` URLs, the SPA's start-process route, and the MCP tool catalog. Leaving it as `personRegistration` while everything else talks about vehicles is the exact "name vs. behavior" disjoint we're trying to delete. H2 history is not something a POC needs to protect; the `seed-history` script can regenerate any demo state we care about. The cost is one grep — `cib7/**/*.{java,bpmn}`, `frontend/src/api/*.ts`, `mcp/src/**`, `scripts/seed-history.*` — and a docker compose volume wipe.

**Constraint:** keep the rename in the *last* PR so the eight preceding PRs don't have to rewrite themselves twice.

### D2 — State-fee payment: fake bank-payment screen, not a "Mark as paid" button

**Decision:** ship a stub PSP, but render it as a believable Estonian bank-payment screen. Path `/pay/{piId}` shows the invoice PDF inline + a fake SEPA panel (recipient IBAN, reference number, amount, sender bank selector) + a single "Confirm payment" CTA that POSTs to `/api/payments/{piId}/confirm` and correlates the `PaymentReceived` message.

**Why:** the engineering cost is the same as a one-button stub — one React page + one Spring controller + one BPMN receive task. The demo cost is wildly different: "Mark as paid" tells the viewer "this is a POC button"; a SEPA-styled screen tells them "this is how the production version will look." We get the BPMN pattern (synchronous wait for external message), the front-end pattern (action-driven page), and the demo polish, all for the same code.

**Out of scope:** real PSP integration (Maksekeskus, Stripe). The fake screen makes no network calls except to our own backend.

### D3 — VAT registration sub-process: defer entirely, do not stub

**Decision:** drop it from this plan. Remove point #6 from "Targeted extensions" in the OÜ flow. Mention it in a one-line "future work" note in the OÜ service README after the rename PRs land.

**Why:** the user explicitly picked "Polish + targeted extensions" (not "Full extension with sub-process") in the planning step. Adding a stub call-activity now would either be a half-finished BPMN (which the project's own [`CLAUDE.md`](../../CLAUDE.md) rule prohibits — no half-finished implementations) or a real sub-process that drags PR #9 out of scope. The call-activity story is a great follow-up — but a follow-up. Today's eight-PR plan is already a meaningful unit of work.

### D4 — Vehicle catalog: move the lookup behind our own backend, not restful-api.dev

**Decision:** add `GET /api/vehicle-registry/{vin}` to the `backend` service (or `cib7` if simpler — implementer's call). It returns `{make, model, year, value, fuelType, registrationStatus}` for a small curated list of believable Estonian-market vehicles (VW Golf, Skoda Octavia, Tesla Model 3, etc.). Unknown VIN → 404, which the existing `Task_LookupVehicle` JUEL output already handles via the `9999` fallback that routes the case to review.

**Why:** the two competing concerns from the open question both win:
- **External HTTP call talking point** is preserved — `Task_LookupVehicle` is still an http-connector service task hitting a real URL, still demonstrates JUEL response parsing, still has the documented missing-field fallback. The endpoint is just *ours* instead of `restful-api.dev`'s.
- **Demo believability** is dramatically better — a viewer sees "VW Golf 2018 · €8,400" instead of "Google Pixel 8 Pro · $1,099" and stops asking what they're looking at.

**Bonus:** we control the schema, so we can include `vehicleAgeYears` (D3 plan) directly from the API instead of asking the applicant to type it. Move `vehicleAgeYears` from form input to derived-from-registry in the BPMN form-pre-population step — that's one less form field and a more realistic flow ("the registry already knows when the car was made").

**Folder/module rename:** `frontend/src/api/objectsApi.ts` → `vehicleRegistryApi.ts`; `backend` (or `cib7`) gets a new `VehicleRegistryController` with the hard-coded catalog.

---

## Knock-on changes from the resolutions

The four decisions ripple back into the body of the plan:

- **D1** confirms cross-cutting work item #5 (process-key rename) as a definite yes in PR #8, not "defer to implementer".
- **D2** upgrades `Task_WaitForPayment` from a stub to a small but real SPA page — add one bullet to the personReg "Targeted extensions" #1 describing the page shape.
- **D3** deletes `businessRegistration` "Targeted extensions" #6 entirely. The follow-up note about VAT sub-processes belongs in the post-implementation README, not this plan.
- **D4** changes the naming-map row about `restful-api.dev` from "rebrand the SPA labels" to "move the lookup behind our own backend endpoint" — and removes `vehicleAgeYears` from the form-change list (it's now derived from the registry response).
