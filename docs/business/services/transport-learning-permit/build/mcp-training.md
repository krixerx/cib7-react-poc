# transportLearningPermit — guidance for the LLM

An applicant requests a driving learning license from the General Traffic
Department of the Transport Authority (ITS Demo Scenario 2). The service
fee is a flat 6 EUR, payable after approval. The system fetches the
applicant's eye-test result from the approved-opticians registry by civil
number and checks the terms of service; weak vision routes the case to a
Police Hospital medical assessment instead of rejecting outright. After
approval and payment the electronic learning license (with the payment
receipt) is issued, stored, and emailed.

- Tell the user the **service fee is 6 EUR** before starting the case.
- Ask for the civil number early — it drives the eye-test lookup. The
  seeded demo civil IDs (`90000001`…`90000005`) each trigger a different
  branch; any other 8-digit ID sails through.
- The age rules are hard: under 18 never qualifies, under 21 cannot apply
  for `heavy-vehicle` or `mechanical-equipment`. Warn instead of
  submitting a doomed application.
- If the case parks on "Police Hospital medical assessment", explain that
  the applicant was flagged for weak vision and the hospital must confirm
  fitness — in the POC the back-office user completes that task.
- After approval the case waits for the fee payment — surface the
  `/pay/{processInstanceId}` link.

## What to ask the user for

To start a transportLearningPermit, you need these pieces of information:

- **applicantName** — full name as on the civil ID.
- **applicantEmail** — where all notifications are sent.
- **civilId** — civil number, exactly 8 digits (drives the
  eye-test lookup; see the demo IDs above).
- **age** — in years; 18+ required, 21+ for heavy vehicle / mechanical
  equipment.
- **residencyStatus** — citizen / gcc-citizen / resident.
- **hasResidentCard** — boolean; required true for GCC-country citizens.
- **licenseCategory** — light-vehicle / motorcycle / heavy-vehicle /
  mechanical-equipment.
- **specialNeeds** — boolean; special-needs applicants are not eligible
  for heavy-vehicle, mechanical-equipment, or motorcycle.
- **profession** — optional, for residents (Ministry of Labor matching
  is informational in the POC); pass an empty string when unknown.

Do NOT include `initiator` in your start_process call. The engine sets it
from the authenticated Keycloak user automatically.

## Eligibility rules (DMN)

Rejections: outstanding traffic restrictions; already holding a valid
(temporary) license; under 18; under 21 for heavy/mechanical; special
needs with heavy/mechanical/motorcycle; GCC citizen without a resident
card; failed eye test; no eye test on file. A "weak" eye-test result is
NOT a rejection — it routes to the Police Hospital medical assessment,
whose positive result proceeds to payment and negative result rejects.

## After start_process

The engine creates the "Apply for a learning permit" user task assigned
to the applicant. Completing it triggers the driver-clearance lookup and
the terms-of-service DMN automatically. There is no send-back loop —
rejected applicants start a new case.

## Status interpretation

- Process `running`, "Apply for a learning permit" open → the applicant
  hasn't confirmed yet.
- Process `running`, no tasks open → in transit through the clearance
  lookup / DMN / emails / PDF pipeline.
- Process `running`, "Police Hospital medical assessment" open → weak
  vision was reported; the hospital board must record the result.
- Process `running`, "Wait for fee payment" active → approved; the
  applicant must pay 6 EUR at `/pay/{processInstanceId}`.
- Process `completed` via "Learning permit issued" → the electronic
  license and receipt were emailed.
- Process `completed` via "Application rejected" → see the rejection
  email for the reason.

## Common applicant questions

- *"How much does it cost?"* — flat 6 EUR, paid after approval.
- *"Why do I have to go to the Police Hospital?"* — the approved
  optician reported weak vision; the hospital confirms eligibility and
  sends the result to the system automatically.
- *"Can I take the eye test in the form?"* — no; the result must come
  electronically from an approved optical shop, looked up by civil
  number.
