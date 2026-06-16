# Transport Vehicle Registration

**Status:** active (POC demo — ITS Demo Scenario 1)
**Process key:** `transportVehicleRegistration`
**BPMN:** [`cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-registration.bpmn`](../../../../cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-registration.bpmn)
**DMN:** [`cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-eligibility.dmn`](../../../../cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-eligibility.dmn),
[`cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-fee.dmn`](../../../../cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-fee.dmn)

**When to read this:** before changing the transportVehicleRegistration flow, its
forms, or its integrations. The service implements **Demo Scenario 1: New
Vehicle Registration** from the Transport Authority "Implementation of Integrated Traffic
System, Presentation and Demonstration" document. Cross-cutting topics live
in [`../../../architecture.md`](../../../architecture.md),
[`../../../cib7.md`](../../../cib7.md), and
[`../../../frontend.md`](../../../frontend.md).

## What this service does

A service requester (citizen, resident, or diplomatic mission) registers a
new vehicle with the Transport Authority of the Transport Authority.
The applicant picks the vehicle category — the **registration fee for the
selected category is shown in the form before submitting** (fee schedule
from the demo document, in EUR). On submission the system checks the
vehicle's technical inspection, insurance, and outstanding restrictions
(fines / circulars) against the clearance registry, then validates the
local registration conditions with a DMN rule table (residents cannot
register commercial vehicles, visitors cannot register at all, diplomatic
plates need MOFA standing, …). Failing any condition sends the applicant a
rejection notice with the reason and ends the case. Passing routes the case
to a **traffic officer** who can approve, reject (with reason), or return
the application for corrections (loops back to the applicant with the
notes). On approval the applicant is emailed a payment request; once the
fee is paid on the public payment page, the system allocates a plate
number, generates the registration certificate PDF, stores it, emails it
to the applicant with plate-collection instructions, and finally sends a
service-level evaluation request — mirroring steps 1–6 of the demo
scenario's service path.

## Flow

```
start              started "Application submitted"          initiator=initiator
user-task          transport-vehicle-application "Submit vehicle registration application"  form=transport-vehicle-application role=initiator
service-task       transport-clearance-check "Check inspection, insurance & restrictions"   (see service-tasks/transport-clearance-check.md)
business-rule-task transport-vehicle-eligibility "Validate registration conditions"  decision=transport-vehicle-eligibility result=eligibilityDecision
gateway-exclusive  conditions-met "Conditions met?"          default=system-rejected
business-rule-task transport-vehicle-fee "Determine registration fee"  decision=transport-vehicle-fee result=registrationFee
user-task          transport-vehicle-officer-review "Traffic officer review"  form=transport-vehicle-officer-review group=civil-servant
gateway-exclusive  officer-decision "Officer decision?"      default=officer-sendback
service-task       transport-vehicle-sendback-email "Email: returned for corrections"  (see service-tasks/transport-vehicle-sendback-email.md)
service-task       transport-vehicle-rejection-email "Email: application rejected"     (see service-tasks/transport-vehicle-rejection-email.md)
service-task       transport-vehicle-payment-email "Email: pay registration fee"       (see service-tasks/transport-vehicle-payment-email.md)
receive-task       transport-wait-fee-payment "Wait for fee payment"  message=PaymentReceived
service-task       transport-allocate-plate "Allocate plate number"                    (see service-tasks/transport-allocate-plate.md)
service-task       transport-certificate-pdf "Generate registration certificate (PDF)" (see service-tasks/transport-certificate-pdf.md)
service-task       transport-store-certificate "Store certificate"                     (see service-tasks/transport-store-certificate.md)
service-task       transport-certificate-email "Email: certificate & plate collection" (see service-tasks/transport-certificate-email.md)
service-task       transport-evaluation-email "Email: service evaluation request"      (see service-tasks/transport-evaluation-email.md)
end                registered "Vehicle registered"
end                rejected "Application rejected"

flow               start -> transport-vehicle-application
flow               transport-vehicle-application -> transport-clearance-check
flow               transport-clearance-check -> transport-vehicle-eligibility
flow               transport-vehicle-eligibility -> conditions-met
flow               conditions-met -> transport-vehicle-fee            label="conditions met" if=${eligibilityDecision == "ok"}
flow               conditions-met -> transport-vehicle-rejection-email  label="system-rejected (default)"
flow               transport-vehicle-fee -> transport-vehicle-officer-review
flow               transport-vehicle-officer-review -> officer-decision
flow               officer-decision -> transport-vehicle-payment-email   label="approved" if=${decision == "approve"}
flow               officer-decision -> transport-vehicle-rejection-email label="rejected" if=${decision == "reject"}
flow               officer-decision -> transport-vehicle-sendback-email  label="officer-sendback (default)"
flow               transport-vehicle-sendback-email -> transport-vehicle-application
flow               transport-vehicle-rejection-email -> rejected
flow               transport-vehicle-payment-email -> transport-wait-fee-payment
flow               transport-wait-fee-payment -> transport-allocate-plate
flow               transport-allocate-plate -> transport-certificate-pdf
flow               transport-certificate-pdf -> transport-store-certificate
flow               transport-store-certificate -> transport-certificate-email
flow               transport-certificate-email -> transport-evaluation-email
flow               transport-evaluation-email -> registered
```

Note for the builder: `transport-vehicle-rejection-email` has TWO incoming flows
(system rejection from `conditions-met`, officer rejection from
`officer-decision`) — one task, two `<bpmn:incoming>` entries. The
FreeMarker template decides which reason text to render (officer
`rejectionReason` wins; otherwise the DMN's `eligibilityDecision` text IS
the reason — see the service-task spec).

## Forms

| Form id | BPMN task | Audience | Spec |
|---|---|---|---|
| `transport-vehicle-application` | `Task_TransportVehicleApplication` | initiator | [`forms/transport-vehicle-application.md`](forms/transport-vehicle-application.md) |
| `transport-vehicle-officer-review` | `Task_TransportVehicleOfficerReview` | `civil-servant` group (plays the Traffic Officer) | [`forms/transport-vehicle-officer-review.md`](forms/transport-vehicle-officer-review.md) |

## Service tasks

| BPMN task | Kind | Spec |
|---|---|---|
| `Task_TransportClearanceCheck` | http-connector → backend | [`service-tasks/transport-clearance-check.md`](service-tasks/transport-clearance-check.md) |
| `Task_TransportVehicleSendbackEmail` | http-connector → Mailpit | [`service-tasks/transport-vehicle-sendback-email.md`](service-tasks/transport-vehicle-sendback-email.md) |
| `Task_TransportVehicleRejectionEmail` | http-connector → Mailpit | [`service-tasks/transport-vehicle-rejection-email.md`](service-tasks/transport-vehicle-rejection-email.md) |
| `Task_TransportVehiclePaymentEmail` | http-connector → Mailpit | [`service-tasks/transport-vehicle-payment-email.md`](service-tasks/transport-vehicle-payment-email.md) |
| `Task_TransportAllocatePlate` | http-connector → backend | [`service-tasks/transport-allocate-plate.md`](service-tasks/transport-allocate-plate.md) |
| `Task_TransportCertificatePdf` | http-connector → pdf-renderer | [`service-tasks/transport-certificate-pdf.md`](service-tasks/transport-certificate-pdf.md) |
| `Task_TransportStoreCertificate` | http-connector → backend documents | [`service-tasks/transport-store-certificate.md`](service-tasks/transport-store-certificate.md) |
| `Task_TransportCertificateEmail` | http-connector → Mailpit | [`service-tasks/transport-certificate-email.md`](service-tasks/transport-certificate-email.md) |
| `Task_TransportEvaluationEmail` | http-connector → Mailpit | [`service-tasks/transport-evaluation-email.md`](service-tasks/transport-evaluation-email.md) |

## Receive tasks (message correlation)

| BPMN task | Message | Correlation | Triggered by |
|---|---|---|---|
| `Task_TransportWaitFeePayment` | `PaymentReceived` | `processInstanceId` | `POST /api/public/payments/{piId}/confirm` (public `/pay/{piId}` page). The shared `PaymentController` resolves the fee from the `registrationFee` process variable and renders amounts in **EUR** for this definition key. |

## Decisions

| BPMN task | DMN | Spec |
|---|---|---|
| `Task_TransportVehicleEligibility` | `transport-vehicle-eligibility` | [`decisions/transport-vehicle-eligibility.md`](decisions/transport-vehicle-eligibility.md) |
| `Task_TransportVehicleFee` | `transport-vehicle-fee` | [`decisions/transport-vehicle-fee.md`](decisions/transport-vehicle-fee.md) |

## Process variables

| Variable | Set by | Type | Notes |
|---|---|---|---|
| `initiator` | start event | String | Login of the service requester who started the case. |
| `applicantName` | `Task_TransportVehicleApplication` | String | Owner's full name as on the civil ID. |
| `applicantEmail` | `Task_TransportVehicleApplication` | String | Required — payment request, rejection notice, certificate, and evaluation emails go here. |
| `civilId` | `Task_TransportVehicleApplication` | String | civil number, 8 digits (POC: length check only). |
| `residencyStatus` | `Task_TransportVehicleApplication` | String | `citizen` \| `resident` \| `diplomat` \| `visitor`. |
| `registrationType` | `Task_TransportVehicleApplication` | String | `private` \| `commercial` \| `public-utility` \| `diplomatic`. |
| `vehicleCategory` | `Task_TransportVehicleApplication` | String | One of the 12 fee-schedule categories (see the form spec) — drives `transport-vehicle-fee`. |
| `vin` | `Task_TransportVehicleApplication` | String | Chassis number; lookup key for the clearance check. |
| `plateOption` | `Task_TransportVehicleApplication` | String | `random` \| `reserved` (demo: "registered with new (random) number plates or with previously reserved number plates"). |
| `reservedPlateNumber` | `Task_TransportVehicleApplication` | String | Required when `plateOption` = `reserved`, else empty string. |
| `inspectionPassed` | `Task_TransportClearanceCheck` | Boolean | From the clearance registry (technical inspection systems stand-in). |
| `insured` | `Task_TransportClearanceCheck` | Boolean | From the clearance registry (insurance companies stand-in). |
| `restrictionsCleared` | `Task_TransportClearanceCheck` | Boolean | Fines / circulars / expired-vehicle restrictions all fulfilled. |
| `eligibilityDecision` | `Task_TransportVehicleEligibility` (DMN) | String | `"ok"` or a human-readable rejection sentence (doubles as the rejection-notice reason). |
| `registrationFee` | `Task_TransportVehicleFee` (DMN) | Double | Fee in EUR from the demo document's fee schedule. Also rendered by `PaymentController` on the pay page. |
| `decision` | `Task_TransportVehicleOfficerReview` | String | `"approve"` \| `"reject"` \| `"sendback"`. |
| `rejectionReason` | `Task_TransportVehicleOfficerReview` | String | Set on officer reject; empty otherwise. Rejection email prefers this over `eligibilityDecision`. |
| `sendBackReason` | `Task_TransportVehicleOfficerReview` | String | Set on officer return-for-corrections; shown as a banner on the applicant form; cleared on resubmit. |
| `paymentReceived` | `PaymentReceived` correlation | Boolean | Written by `PaymentController.confirm`. |
| `plateNumber` | `Task_TransportAllocatePlate` | String | Allocated by the backend plate registry (or the reserved number, validated server-side). |
| `certificatePdfBytes` | `Task_TransportCertificatePdf` | byte[] | Raw certificate PDF — bytes-typed so it spills to `ACT_GE_BYTEARRAY`. |
| `certificatePdfFilename` | `Task_TransportCertificatePdf` | String | e.g. `transport-registration-certificate-<plateNumber>.pdf`. |
| `certificateAttachmentId` | `Task_TransportStoreCertificate` | String | Backend document id (Documents card). |

## Roles and authorization

- **Service requester (applicant)** — Keycloak group `applicant`
  (slash-less engine view). Owns `Task_TransportVehicleApplication` via
  `camunda:assignee="${initiator}"`.
- **Traffic officer** — played by the Keycloak group `civil-servant` in
  this POC (the deployment has two seeded back-office roles; a dedicated
  `traffic-officer` group would need realm + `AuthorizationBootstrap`
  changes — see Known trade-offs). Owns `Task_TransportVehicleOfficerReview` via
  `candidateGroups="civil-servant"`.

`AuthorizationBootstrap.java` already grants both groups wildcard
process-definition permissions (`resourceId="*"`), so no Java change is
needed for this service.

## Known trade-offs

- **Fee is computed twice** — client-side in the form (preview, so the cost
  is visible *before* submitting, per the demo requirement) and
  authoritatively by the `transport-vehicle-fee` DMN. The two tables must be kept
  in sync; the DMN is the source of truth for what is actually charged.
- **`civil-servant` plays the Traffic Officer.** A faithful build would add
  a `traffic-officer` Keycloak group; the POC reuses the existing
  back-office group so the seeded `homer` account can demo the scenario.
- **The clearance registry is a backend stand-in** seeded with demo VINs
  (`DEMOFAILINSP01` fails inspection, `DEMONOINSURE02` is uninsured,
  `DEMOFINESDUE03` has outstanding fines; any other VIN is all-clear).
  Real ITS would integrate technical inspection systems, insurance
  companies, and the fines system through the Transport Authority Central Integration
  Platform.
- **No MOFA / Ministry of Transport approval sub-flows.** Diplomatic and
  public-utility special conditions are reduced to DMN rows + officer
  judgment; the demo document's inter-ministry linkages are out of POC
  scope.
- **SMS / app notifications are email-only** (Mailpit). The demo document
  lists email + SMS + application notification; the POC sends email and
  treats the channel fan-out as a notification-gateway concern.
- **Plate format is simplified** (`NNNNN AB`); the local plate taxonomy
  (special plates, benefit numbers, three-plate resident cap) is not
  modeled beyond the reserved-plate option.

## LLM guidance

- Always tell the user the registration fee for their chosen category
  **before** calling start_process — the fee schedule is in the
  `describe_service` output; quoting it up front mirrors the form's fee
  preview.
- `vin` drives the clearance check. The demo VINs `DEMOFAILINSP01`,
  `DEMONOINSURE02`, and `DEMOFINESDUE03` deliberately fail; any
  other plausible VIN passes.
- `residencyStatus` = `visitor` is always rejected (demo rule: visitors
  must hold a residence card). Residents cannot register `commercial`
  vehicles. Warn the user instead of submitting a doomed application.
- After officer approval the case waits on the fee payment — surface the
  `/pay/{processInstanceId}` link when the user asks why nothing is
  happening.
- If the case is returned for corrections, `query_user_history('sendBackReason')`
  has the officer's notes; offer to fix and resubmit the same case.

## Flow diagram

The block below is generated from the BPMN by
[`scripts/bpmn-to-mermaid.mjs`](../../../../scripts/bpmn-to-mermaid.mjs).
Do not edit between the markers — run the script to refresh:

```sh
cd scripts
node bpmn-to-mermaid.mjs \
  ../cib7/src/main/resources/processes/transport-vehicle-registration/transport-vehicle-registration.bpmn \
  --out ../docs/business/services/transport-vehicle-registration/README.md
```

<!-- bpmn-diagram:start -->
```mermaid
flowchart LR
  %% Transport Vehicle Registration
  StartEvent_1(("Application submitted"))
  Task_TransportVehicleApplication["👤 Submit vehicle registration application"]
  Task_TransportVehicleOfficerReview["👤 Traffic officer review"]
  Task_TransportClearanceCheck[["🔌 Check inspection, insurance & restrictions"]]
  Task_TransportVehicleSendbackEmail[["🔌 Email: returned for corrections"]]
  Task_TransportVehicleRejectionEmail[["🔌 Email: application rejected"]]
  Task_TransportVehiclePaymentEmail[["🔌 Email: pay registration fee"]]
  Task_TransportAllocatePlate[["🔌 Allocate plate number"]]
  Task_TransportCertificatePdf[["🔌 Generate registration certificate (PDF)"]]
  Task_TransportStoreCertificate[["🔌 Store certificate"]]
  Task_TransportCertificateEmail[["🔌 Email: certificate & plate collection"]]
  Task_TransportEvaluationEmail[["🔌 Email: service evaluation request"]]
  Task_TransportVehicleEligibility[/"📋 Validate registration conditions"/]
  Task_TransportVehicleFee[/"📋 Determine registration fee"/]
  Gateway_ConditionsMet{"Conditions met?"}
  Gateway_OfficerDecision{"Officer decision?"}
  EndEvent_Rejected((("Application rejected")))
  EndEvent_Registered((("Vehicle registered")))
  Task_TransportWaitFeePayment[["📥 Wait for fee payment"]]
  StartEvent_1 --> Task_TransportVehicleApplication
  Task_TransportVehicleApplication --> Task_TransportClearanceCheck
  Task_TransportClearanceCheck --> Task_TransportVehicleEligibility
  Task_TransportVehicleEligibility --> Gateway_ConditionsMet
  Gateway_ConditionsMet -- "conditions met" --> Task_TransportVehicleFee
  Gateway_ConditionsMet -. "rejected (default)" .-> Task_TransportVehicleRejectionEmail
  Task_TransportVehicleFee --> Task_TransportVehicleOfficerReview
  Task_TransportVehicleOfficerReview --> Gateway_OfficerDecision
  Gateway_OfficerDecision -- "approved" --> Task_TransportVehiclePaymentEmail
  Gateway_OfficerDecision -- "rejected" --> Task_TransportVehicleRejectionEmail
  Gateway_OfficerDecision -. "returned (default)" .-> Task_TransportVehicleSendbackEmail
  Task_TransportVehicleSendbackEmail --> Task_TransportVehicleApplication
  Task_TransportVehicleRejectionEmail --> EndEvent_Rejected
  Task_TransportVehiclePaymentEmail --> Task_TransportWaitFeePayment
  Task_TransportWaitFeePayment --> Task_TransportAllocatePlate
  Task_TransportAllocatePlate --> Task_TransportCertificatePdf
  Task_TransportCertificatePdf --> Task_TransportStoreCertificate
  Task_TransportStoreCertificate --> Task_TransportCertificateEmail
  Task_TransportCertificateEmail --> Task_TransportEvaluationEmail
  Task_TransportEvaluationEmail --> EndEvent_Registered
```
<!-- bpmn-diagram:end -->
