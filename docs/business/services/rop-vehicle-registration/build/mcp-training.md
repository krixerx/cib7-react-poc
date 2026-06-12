# ropVehicleRegistration — guidance for the LLM

A service requester registers a new vehicle with the General Traffic
Department of the Royal Oman Police (ITS Demo Scenario 1). The
registration fee for the chosen vehicle category is known up front. The
system checks the vehicle's technical inspection, insurance, and
outstanding restrictions, validates the Omani registration conditions,
then routes to a traffic officer who can approve, reject with a reason,
or return the application for corrections. After approval the applicant
pays the fee on a public payment page; the system then allocates a plate
number, generates the registration certificate PDF, and emails it with
plate-collection instructions, followed by a service evaluation request.

- Always tell the user the registration fee for their chosen category
  **before** calling start_process — the fee schedule is in the
  `describe_service` output; quoting it up front mirrors the form's fee
  preview.
- `vin` drives the clearance check. The demo VINs `ROPDEMOFAILINSP01`,
  `ROPDEMONOINSURE02`, and `ROPDEMOFINESDUE03` deliberately fail; any
  other plausible VIN passes.
- `residencyStatus` = `visitor` is always rejected (demo rule: visitors
  must hold a residence card). Residents cannot register `commercial`
  vehicles. Warn the user instead of submitting a doomed application.
- After officer approval the case waits on the fee payment — surface the
  `/pay/{processInstanceId}` link when the user asks why nothing is
  happening.
- If the case is returned for corrections, `query_user_history('sendBackReason')`
  has the officer's notes; offer to fix and resubmit the same case.

## What to ask the user for

To start a ropVehicleRegistration, you need these pieces of information:

- **applicantName** — the owner's full name as on the civil ID.
- **applicantEmail** — where the payment request, certificate, and all
  notices are sent.
- **civilId** — Omani civil number, exactly 8 digits.
- **residencyStatus** — citizen / resident / diplomat / visitor.
- **registrationType** — private / commercial / public-utility /
  diplomatic.
- **vehicleCategory** — one of the 12 fee-schedule categories; fees in
  OMR: motorcycle 10; private cars by engine size 15 / 20 / 30 / 50;
  electric by motor power 15 / 20 / 30 / 50; tractor 40; truck 3–5 t
  120; truck 5 t+ 180. **State the fee before starting.**
- **vin** — chassis number, 11–17 letters/digits.
- **plateOption** — `random` for a new plate, `reserved` with
  **reservedPlateNumber** for a previously reserved one (pass an empty
  string for random).

Do NOT include `initiator` in your start_process call. The engine sets it
from the authenticated Keycloak user automatically.

## Eligibility rules (DMN)

There is no auto-approval: every application that passes the system
checks goes to a traffic officer. The system check rejects when: the
vehicle failed the technical inspection, is uninsured, has outstanding
restrictions (fines/circulars), the applicant is a visitor, a resident
requests commercial registration, or a non-diplomat requests diplomatic
registration. The rejection email carries the exact reason.

## After start_process

The engine creates the "Submit vehicle registration application" user
task assigned to the applicant. The variables you passed at start time
are pre-filled; completing the task with the same values advances the
case. The clearance check, eligibility DMN, and fee DMN run
automatically; eligible cases land in the traffic officer's queue.

If the case is returned for corrections, the applicant returns to the
same task to correct and resubmit. The officer's notes surface via
`query_user_history('sendBackReason')`.

## Status interpretation

- Process `running`, "Submit vehicle registration application" open →
  the applicant hasn't confirmed (or it was returned for corrections —
  check `sendBackReason`).
- Process `running`, no tasks open → in transit through the clearance
  check / DMNs / emails / PDF pipeline.
- Process `running`, "Traffic officer review" open → with the back
  office.
- Process `running`, "Wait for fee payment" active → approved; the
  applicant must pay at `/pay/{processInstanceId}` (amount in OMR).
- Process `completed` via "Vehicle registered" → plate allocated and
  certificate emailed.
- Process `completed` via "Application rejected" → see the rejection
  email for the reason.

## Common applicant questions

- *"How much does it cost?"* — by category, 10–180 OMR; see the fee
  schedule above. The exact amount is also on the payment page.
- *"Why was I rejected without a person looking at it?"* — the system
  validates inspection, insurance, restrictions, and the registration
  conditions before any officer sees the case; the rejection email
  carries the exact reason and a new application can be submitted once
  it is resolved.
- *"Where are my plates?"* — after payment the plate number is allocated
  and emailed with the certificate; physical plates are collected from
  the license-plate printing company or a service center.
