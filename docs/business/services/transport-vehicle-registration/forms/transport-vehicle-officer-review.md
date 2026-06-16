# Form: `transport-vehicle-officer-review`

**Form id:** `transport-vehicle-officer-review` (kebab-case, globally unique)
**BPMN task:** `Task_TransportVehicleOfficerReview`
**Audience:** `civil-servant` group (plays the Transport Authority Traffic Officer)
**Mode:** `review` (read-only data display + outcome buttons)

## Intro

"Review the vehicle registration application. The system has already
verified the technical inspection, insurance, and restrictions. Approve to
request the fee payment, return the application for corrections, or reject
it with a reason." (Demo scenario: *"Possible action: Approve the request /
Reject the request / Return to request an inquiry about the request."*)

## Read-only display

Rendered as a definition list from `data` (all inputs `disabled`):

| Label | Variable |
|---|---|
| Owner | `applicantName` |
| Email | `applicantEmail` |
| Civil number | `civilId` |
| Residency status | `residencyStatus` |
| Registration type | `registrationType` |
| Vehicle category | `vehicleCategory` |
| Chassis number (VIN) | `vin` |
| Plate request | `plateOption` (+ `reservedPlateNumber` when reserved) |
| Technical inspection | `inspectionPassed` (✓ / ✗) |
| Insurance | `insured` (✓ / ✗) |
| Restrictions cleared | `restrictionsCleared` (✓ / ✗) |
| Registration fee | `registrationFee` + " EUR" |

## Fields (officer input)

| Field name | UI label | Input type | Required | Default | Validation |
|---|---|---|---|---|---|
| `sendBackReason` | `Notes to the applicant` | `textarea` | only for **Return for corrections** | empty | non-empty when returning |
| `rejectionReason` | `Rejection reason` | `textarea` | only for **Reject** | empty | non-empty when rejecting |

The two textareas can be a single "Reason / notes" textarea whose value is
routed to the matching variable by the button pressed — implementer's
choice, as long as the completed variables match the Actions table.

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Approve` | not submitting | `decision="approve":String, rejectionReason="":String, sendBackReason="":String` |
| `Return for corrections` | reason text non-empty | `decision="sendback":String, sendBackReason:String, rejectionReason="":String` |
| `Reject` | reason text non-empty | `decision="reject":String, rejectionReason:String, sendBackReason="":String` |

## Send-back loop

`on-send-back: none` — this form is never the target of a send-back.

## Read-only mode

When `readOnly` is true the action row is hidden; the display list and the
captured `decision` / reasons render as plain text.

## Notes

- The unused reason variable is always completed as an empty string so the
  rejection-email template's "officer reason wins, else DMN text" logic
  never sees a stale value from a previous loop iteration.
- Approving does NOT end the case — it advances to the fee-payment request
  (demo step 3 "Pay fees" happens after officer approval).
