# ROP Driving Learning Permit (Oman)

**Status:** active (POC demo — ROP ITS Demo Scenario 2)
**Process key:** `ropLearningPermit`
**BPMN:** [`cib7/src/main/resources/processes/rop-learning-permit.bpmn`](../../../../cib7/src/main/resources/processes/rop-learning-permit.bpmn)
**DMN:** [`cib7/src/main/resources/processes/rop-permit-eligibility.dmn`](../../../../cib7/src/main/resources/processes/rop-permit-eligibility.dmn)

**When to read this:** before changing the ropLearningPermit flow, its
forms, or its integrations. The service implements **Demo Scenario 2:
Issue of a new Learning Permit** from the ROP "Implementation of
Integrated Traffic System, Presentation and Demonstration" document.

## What this service does

An applicant requests a driving learning license from the General Traffic
Department. The **flat service fee of 6 OMR is shown in the form before
submitting**. On submission the system pulls the applicant's eye-test
result from the approved-opticians registry plus their license and
restrictions status (driver clearance stand-in), then evaluates the
demo's terms of service in a DMN: minimum age 18 (21 for heavy vehicles
and mechanical equipment), no valid temporary license already held, GCC
citizens need a resident card, special-needs applicants are not eligible
for heavy/mechanical/motorcycle categories, and the eye test must be on
file and passed. A failed condition emails a rejection notice with the
reason and ends the case. A **weak-vision** result routes the case to the
Police Hospital (demo: *"the system sends a notification to the service
applicant to go to the police hospital"*) — the hospital's medical board
records a positive or negative assessment; negative rejects the case,
positive proceeds. Eligible applicants are emailed a payment request;
once the 6 OMR fee is paid on the public payment page, the system issues
the permit (number + 1-year validity, persisted in the backend), renders
the electronic learning license PDF with the payment receipt, stores it,
emails it to the applicant, and sends the service-level evaluation
request — mirroring the demo's service path steps 1–6.

## Flow

```
start              started "Application submitted"          initiator=initiator
user-task          rop-permit-application "Apply for a learning permit"  form=rop-permit-application role=initiator
service-task       rop-driver-clearance "Fetch eye test, license & restrictions status"  (see service-tasks/rop-driver-clearance.md)
business-rule-task rop-permit-eligibility "Check terms of service"  decision=rop-permit-eligibility result=permitDecision
gateway-exclusive  permit-conditions "Conditions met?"       default=permit-rejected
service-task       rop-hospital-notice-email "Email: visit the Police Hospital"  (see service-tasks/rop-hospital-notice-email.md)
user-task          rop-hospital-assessment "Police Hospital medical assessment"  form=rop-hospital-assessment group=civil-servant
gateway-exclusive  medical-result "Medical result?"          default=medical-negative
service-task       rop-permit-rejection-email "Email: application rejected"  (see service-tasks/rop-permit-rejection-email.md)
service-task       rop-permit-payment-email "Email: pay the service fee"     (see service-tasks/rop-permit-payment-email.md)
receive-task       rop-wait-permit-payment "Wait for fee payment"  message=PaymentReceived
service-task       rop-issue-permit "Issue learning permit"                  (see service-tasks/rop-issue-permit.md)
service-task       rop-permit-pdf "Generate electronic learning license (PDF)"  (see service-tasks/rop-permit-pdf.md)
service-task       rop-store-permit "Store learning license"                 (see service-tasks/rop-store-permit.md)
service-task       rop-permit-email "Email: electronic license & receipt"    (see service-tasks/rop-permit-email.md)
service-task       rop-permit-evaluation-email "Email: service evaluation request"  (see service-tasks/rop-permit-evaluation-email.md)
end                issued "Learning permit issued"
end                rejected "Application rejected"

flow               start -> rop-permit-application
flow               rop-permit-application -> rop-driver-clearance
flow               rop-driver-clearance -> rop-permit-eligibility
flow               rop-permit-eligibility -> permit-conditions
flow               permit-conditions -> rop-permit-payment-email    label="conditions met" if=${permitDecision == "ok"}
flow               permit-conditions -> rop-hospital-notice-email   label="weak vision" if=${permitDecision == "medical"}
flow               permit-conditions -> rop-permit-rejection-email  label="permit-rejected (default)"
flow               rop-hospital-notice-email -> rop-hospital-assessment
flow               rop-hospital-assessment -> medical-result
flow               medical-result -> rop-permit-payment-email       label="fit to drive" if=${medicalResult == "positive"}
flow               medical-result -> rop-permit-rejection-email     label="medical-negative (default)"
flow               rop-permit-rejection-email -> rejected
flow               rop-permit-payment-email -> rop-wait-permit-payment
flow               rop-wait-permit-payment -> rop-issue-permit
flow               rop-issue-permit -> rop-permit-pdf
flow               rop-permit-pdf -> rop-store-permit
flow               rop-store-permit -> rop-permit-email
flow               rop-permit-email -> rop-permit-evaluation-email
flow               rop-permit-evaluation-email -> issued
```

Note for the builder: `rop-permit-rejection-email` and
`rop-permit-payment-email` each have TWO incoming flows (DMN gateway and
medical-result gateway) — one task each, two `<bpmn:incoming>` entries.
There is **no send-back loop** in this service (the demo scenario has
none); a rejected applicant starts a new case.

## Forms

| Form id | BPMN task | Audience | Spec |
|---|---|---|---|
| `rop-permit-application` | `Task_RopPermitApplication` | initiator | [`forms/rop-permit-application.md`](forms/rop-permit-application.md) |
| `rop-hospital-assessment` | `Task_RopHospitalAssessment` | `civil-servant` group (plays the Police Hospital medical board) | [`forms/rop-hospital-assessment.md`](forms/rop-hospital-assessment.md) |

## Service tasks

| BPMN task | Kind | Spec |
|---|---|---|
| `Task_RopDriverClearance` | http-connector → backend | [`service-tasks/rop-driver-clearance.md`](service-tasks/rop-driver-clearance.md) |
| `Task_RopHospitalNoticeEmail` | http-connector → Mailpit | [`service-tasks/rop-hospital-notice-email.md`](service-tasks/rop-hospital-notice-email.md) |
| `Task_RopPermitRejectionEmail` | http-connector → Mailpit | [`service-tasks/rop-permit-rejection-email.md`](service-tasks/rop-permit-rejection-email.md) |
| `Task_RopPermitPaymentEmail` | http-connector → Mailpit | [`service-tasks/rop-permit-payment-email.md`](service-tasks/rop-permit-payment-email.md) |
| `Task_RopIssuePermit` | http-connector → backend | [`service-tasks/rop-issue-permit.md`](service-tasks/rop-issue-permit.md) |
| `Task_RopPermitPdf` | http-connector → pdf-renderer | [`service-tasks/rop-permit-pdf.md`](service-tasks/rop-permit-pdf.md) |
| `Task_RopStorePermit` | http-connector → backend documents | [`service-tasks/rop-store-permit.md`](service-tasks/rop-store-permit.md) |
| `Task_RopPermitEmail` | http-connector → Mailpit | [`service-tasks/rop-permit-email.md`](service-tasks/rop-permit-email.md) |
| `Task_RopPermitEvaluationEmail` | http-connector → Mailpit | [`service-tasks/rop-permit-evaluation-email.md`](service-tasks/rop-permit-evaluation-email.md) |

## Receive tasks (message correlation)

| BPMN task | Message | Correlation | Triggered by |
|---|---|---|---|
| `Task_RopWaitPermitPayment` | `PaymentReceived` | `processInstanceId` | `POST /api/public/payments/{piId}/confirm` (public `/pay/{piId}` page). The shared `PaymentController` charges the flat **6 OMR** for this definition key. |

## Decisions

| BPMN task | DMN | Spec |
|---|---|---|
| `Task_RopPermitEligibility` | `rop-permit-eligibility` | [`decisions/rop-permit-eligibility.md`](decisions/rop-permit-eligibility.md) |

## Process variables

| Variable | Set by | Type | Notes |
|---|---|---|---|
| `initiator` | start event | String | Login of the applicant. |
| `applicantName` | `Task_RopPermitApplication` | String | Full name as on the civil ID. |
| `applicantEmail` | `Task_RopPermitApplication` | String | Required — all notifications go here. |
| `civilId` | `Task_RopPermitApplication` | String | Omani civil number, 8 digits; lookup key for the driver clearance. |
| `age` | `Task_RopPermitApplication` | Integer | Applicant's age in years (drives the age rules). |
| `residencyStatus` | `Task_RopPermitApplication` | String | `citizen` \| `gcc-citizen` \| `resident`. |
| `hasResidentCard` | `Task_RopPermitApplication` | Boolean | Demo rule: GCC-country citizens must hold a resident card. |
| `licenseCategory` | `Task_RopPermitApplication` | String | `light-vehicle` \| `motorcycle` \| `heavy-vehicle` \| `mechanical-equipment`. |
| `specialNeeds` | `Task_RopPermitApplication` | Boolean | Routes the demo's special-needs restrictions. |
| `profession` | `Task_RopPermitApplication` | String | Optional; demo links residents' license category to the Ministry of Labor profession — informational in the POC. |
| `eyeTestResult` | `Task_RopDriverClearance` | String | `pass` \| `weak` \| `fail` \| `missing` — from the approved-opticians registry stand-in. |
| `hasValidTemporaryLicense` | `Task_RopDriverClearance` | Boolean | Demo rule: must not already hold a valid (temporary) license. |
| `restrictionsCleared` | `Task_RopDriverClearance` | Boolean | Violations / circulars cleared. |
| `permitDecision` | `Task_RopPermitEligibility` (DMN) | String | `"ok"`, `"medical"`, or a human-readable rejection sentence. |
| `medicalResult` | `Task_RopHospitalAssessment` | String | `"positive"` \| `"negative"` (Police Hospital). |
| `medicalNotes` | `Task_RopHospitalAssessment` | String | Free-text assessment notes. |
| `rejectionReason` | `Task_RopHospitalAssessment` | String | Set on negative assessment ("Police Hospital medical assessment: …"); empty otherwise. Rejection email prefers this over `permitDecision`. |
| `paymentReceived` | `PaymentReceived` correlation | Boolean | Written by `PaymentController.confirm`. |
| `permitNumber` | `Task_RopIssuePermit` | String | Issued by the backend permit registry. |
| `permitValidUntil` | `Task_RopIssuePermit` | String | ISO date, one year from issue. |
| `permitPdfBytes` | `Task_RopPermitPdf` | byte[] | Raw license PDF — bytes-typed so it spills to `ACT_GE_BYTEARRAY`. |
| `permitPdfFilename` | `Task_RopPermitPdf` | String | e.g. `rop-learning-permit-<permitNumber>.pdf`. |
| `permitAttachmentId` | `Task_RopStorePermit` | String | Backend document id (Documents card). |

## Roles and authorization

- **Applicant** — Keycloak group `applicant`. Owns
  `Task_RopPermitApplication` via `camunda:assignee="${initiator}"`.
- **Police Hospital medical board** — played by the Keycloak group
  `civil-servant` in this POC (same pragmatic mapping as the traffic
  officer in [`../rop-vehicle-registration/`](../rop-vehicle-registration/README.md)).
  Owns `Task_RopHospitalAssessment` via
  `candidateGroups="civil-servant"`.

`AuthorizationBootstrap.java` grants are wildcard — no change needed.

## Known trade-offs

- **The Police Hospital linkage is a human task, not a system link.** The
  demo describes automatic electronic linkage between ITS and the
  hospital system; the POC stands it in with a back-office form so the
  branch is demoable with the seeded `homer` account. The notification
  emails around it match the demo's wording.
- **The opticians registry is a backend stand-in** seeded by civil ID:
  `90000001` → weak vision (medical branch), `90000002` → eye test
  failed, `90000003` → no eye test on file, `90000004` → already holds a
  valid temporary license, `90000005` → outstanding restrictions; any
  other civil ID is all-clear with a passed eye test.
- **Fee is flat 6 OMR** (demo document's "Service Fee: 6 OMR") —
  hard-coded in `PaymentController` for this definition key and stated in
  the form intro; no fee DMN needed.
- **No driving-school / appointment sub-flows.** The demo's optional
  service-center path and the instructor-related services are separate
  services in the RFP catalogue.
- **SMS / app notifications are email-only** (Mailpit), as in the sibling
  ROP service.

## LLM guidance

- Tell the user the **service fee is 6 OMR** before starting the case.
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

## Flow diagram

The block below is generated from the BPMN by
[`scripts/bpmn-to-mermaid.mjs`](../../../../scripts/bpmn-to-mermaid.mjs).
Do not edit between the markers — run the script to refresh:

```sh
cd scripts
node bpmn-to-mermaid.mjs \
  ../cib7/src/main/resources/processes/rop-learning-permit.bpmn \
  --out ../docs/business/services/rop-learning-permit/README.md
```

<!-- bpmn-diagram:start -->
```mermaid
flowchart LR
  %% ROP Driving Learning Permit (Oman)
  StartEvent_1(("Application submitted"))
  Task_RopPermitApplication["👤 Apply for a learning permit"]
  Task_RopHospitalAssessment["👤 Police Hospital medical assessment"]
  Task_RopDriverClearance[["🔌 Fetch eye test, license & restrictions status"]]
  Task_RopHospitalNoticeEmail[["🔌 Email: visit the Police Hospital"]]
  Task_RopPermitRejectionEmail[["🔌 Email: application rejected"]]
  Task_RopPermitPaymentEmail[["🔌 Email: pay the service fee"]]
  Task_RopIssuePermit[["🔌 Issue learning permit"]]
  Task_RopPermitPdf[["🔌 Generate electronic learning license (PDF)"]]
  Task_RopStorePermit[["🔌 Store learning license"]]
  Task_RopPermitEmail[["🔌 Email: electronic license & receipt"]]
  Task_RopPermitEvaluationEmail[["🔌 Email: service evaluation request"]]
  Task_RopPermitEligibility[/"📋 Check terms of service"/]
  Gateway_PermitConditions{"Conditions met?"}
  Gateway_MedicalResult{"Medical result?"}
  EndEvent_Rejected((("Application rejected")))
  EndEvent_Issued((("Learning permit issued")))
  Task_RopWaitPermitPayment[["📥 Wait for fee payment"]]
  StartEvent_1 --> Task_RopPermitApplication
  Task_RopPermitApplication --> Task_RopDriverClearance
  Task_RopDriverClearance --> Task_RopPermitEligibility
  Task_RopPermitEligibility --> Gateway_PermitConditions
  Gateway_PermitConditions -- "conditions met" --> Task_RopPermitPaymentEmail
  Gateway_PermitConditions -- "weak vision" --> Task_RopHospitalNoticeEmail
  Gateway_PermitConditions -. "rejected (default)" .-> Task_RopPermitRejectionEmail
  Task_RopHospitalNoticeEmail --> Task_RopHospitalAssessment
  Task_RopHospitalAssessment --> Gateway_MedicalResult
  Gateway_MedicalResult -- "fit to drive" --> Task_RopPermitPaymentEmail
  Gateway_MedicalResult -. "not fit (default)" .-> Task_RopPermitRejectionEmail
  Task_RopPermitRejectionEmail --> EndEvent_Rejected
  Task_RopPermitPaymentEmail --> Task_RopWaitPermitPayment
  Task_RopWaitPermitPayment --> Task_RopIssuePermit
  Task_RopIssuePermit --> Task_RopPermitPdf
  Task_RopPermitPdf --> Task_RopStorePermit
  Task_RopStorePermit --> Task_RopPermitEmail
  Task_RopPermitEmail --> Task_RopPermitEvaluationEmail
  Task_RopPermitEvaluationEmail --> EndEvent_Issued
```
<!-- bpmn-diagram:end -->
