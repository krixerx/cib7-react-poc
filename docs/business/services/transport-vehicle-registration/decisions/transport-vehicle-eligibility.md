# Decision: `transport-vehicle-eligibility`

**Decision id:** `transport-vehicle-eligibility`
**BPMN business rule task:** `Task_TransportVehicleEligibility`
**Output variable:** `eligibilityDecision` (single entry)
**History TTL:** `P30D` (matches the BPMN's process TTL)

Implements the "General Conditions" and "Special conditions" tables of Transport Authority
Demo Scenario 1 — the demo's step 1: *"The system verifies that the
conditions are met. If the conditions are not met, a rejection notice is
sent to the service applicant with the reasons for the rejection."*

## Inputs

| Input | Type | Source variable |
|---|---|---|
| Restrictions cleared | `boolean` | `restrictionsCleared` |
| Inspection passed | `boolean` | `inspectionPassed` |
| Insured | `boolean` | `insured` |
| Residency status | `string` | `residencyStatus` |
| Registration type | `string` | `registrationType` |

## Output

| Output | Type | Name | Values |
|---|---|---|---|
| Eligibility | `string` | `eligibilityDecision` | `"ok"` or a human-readable rejection sentence |

The non-`"ok"` output **is** the rejection reason — the rejection-notice
email renders it verbatim (unless the officer's `rejectionReason` is set,
which only happens later in the flow).

## Hit policy

`FIRST` — top-to-bottom, first match wins, mapped to `eligibilityDecision`
via `singleEntry`.

## Rules

| # | Restrictions cleared | Inspection passed | Insured | Residency status | Registration type | Eligibility |
|---|---|---|---|---|---|---|
| 1 | `false` | `-` | `-` | `-` | `-` | `"Outstanding restrictions (unpaid fines, circulars, or expired vehicles) must be fulfilled before registration."` |
| 2 | `-` | `false` | `-` | `-` | `-` | `"The vehicle has not passed the technical inspection."` |
| 3 | `-` | `-` | `false` | `-` | `-` | `"The vehicle is not insured. Valid vehicle insurance is required."` |
| 4 | `-` | `-` | `-` | `"visitor"` | `-` | `"Vehicles are not registered for visitors and passersby; a residence card is required."` |
| 5 | `-` | `-` | `-` | `"resident"` | `"commercial"` | `"Registration of commercial vehicles for residents is not permitted."` |
| 6 | `-` | `-` | `-` | not(`"diplomat"`) | `"diplomatic"` | `"Diplomatic registration is reserved for diplomatic missions and international organizations (Ministry of Foreign Affairs approval)."` |
| 7 | `-` | `-` | `-` | `-` | `-` | `"ok"` |

Rule 7 is the explicit catch-all, so the decision never returns `null` and
the gateway's `${eligibilityDecision == "ok"}` condition is always safe.

## Why these rules

- **Rules 1–3** are the demo's General Conditions 2, 3, and 7 (inspection,
  insurance, fulfillment of restrictions), checked against the clearance
  registry by the preceding service task.
- **Rule 4** is Special condition for private vehicles #4 (no registration
  for visitors).
- **Rule 5** is Special condition for commercial vehicles #1.
- **Rule 6** compresses the diplomatic-missions conditions: only a
  diplomatic-mission applicant may request diplomatic registration; the
  MOFA approval itself is folded into the traffic-officer review in this
  POC.
- Conditions the POC does **not** encode (pickup/bus for residents, the
  three-special-plates cap, agency sale letters, customs export
  certificates) are left to the traffic officer's manual review — listed
  in the README's Known trade-offs.
