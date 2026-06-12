# Form: `rop-vehicle-application`

**Form id:** `rop-vehicle-application` (kebab-case, globally unique)
**BPMN task:** `Task_RopVehicleApplication`
**Audience:** `initiator`
**Mode:** `entry` (collects new data; supports send-back resubmit too)

## Intro

First-submit: "Register a new vehicle with the General Traffic Department
(Royal Oman Police). The registration fee for your vehicle category is
shown below before you submit."
Resubmission: "Your application was returned by the traffic officer.
Update the details below and resubmit."

## Fee preview (cost visible before submitting)

The form renders a highlighted **"Registration fee: X OMR"** line that
updates live as the user changes `vehicleCategory`. The client-side table
mirrors the `rop-vehicle-fee` DMN (the DMN stays authoritative):

| `vehicleCategory` value | UI label | Fee (OMR) |
|---|---|---|
| `motorcycle` | Motorcycle | 10 |
| `private-lt1500` | Private car — engine under 1500 cm³ | 15 |
| `private-1500-3000` | Private car — engine 1500–3000 cm³ | 20 |
| `private-3000-4500` | Private car — engine 3000–4500 cm³ | 30 |
| `private-gt4500` | Private car — engine over 4500 cm³ | 50 |
| `electric-lt75kw` | Electric — motor under 75 kW | 15 |
| `electric-75-150kw` | Electric — motor 75–150 kW | 20 |
| `electric-150-224kw` | Electric — motor 150–224 kW | 30 |
| `electric-gt224kw` | Electric — motor over 224 kW | 50 |
| `tractor` | Tractor | 40 |
| `truck-3-5t` | Truck — 3 to 5 tons | 120 |
| `truck-gte5t` | Truck — 5 tons and above | 180 |

(Fee schedule from the ROP demo document, "Service Fee" table.)

## Fields

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `applicantName` | `Owner full name` | `text` | yes | `data.applicantName` | non-empty, trim |
| `applicantEmail` | `Email address` | `email` | yes | `data.applicantEmail` | non-empty, must contain `@` |
| `civilId` | `Civil number` | `text` | yes | `data.civilId` | exactly 8 digits |
| `residencyStatus` | `Residency status` | `select` (`citizen` Omani citizen \| `resident` Resident \| `diplomat` Diplomatic mission \| `visitor` Visitor) | yes | `data.residencyStatus` (default `citizen`) | one of the four values |
| `registrationType` | `Registration type` | `select` (`private` Private \| `commercial` Commercial \| `public-utility` Public utility (taxi / education / crane) \| `diplomatic` Diplomatic) | yes | `data.registrationType` (default `private`) | one of the four values |
| `vehicleCategory` | `Vehicle category` | `select` (12 options from the fee table above, fee shown in each option label) | yes | `data.vehicleCategory` (default `private-lt1500`) | one of the twelve values |
| `vin` | `Chassis number (VIN)` | `text` | yes | `data.vin` | 11–17 alphanumeric chars, uppercased on input |
| `plateOption` | `Plate number` | `select` (`random` New random plate \| `reserved` Previously reserved plate) | yes | `data.plateOption` (default `random`) | one of the two values |
| `reservedPlateNumber` | `Reserved plate number` | `text` | only when `plateOption` = `reserved` | `data.reservedPlateNumber` | non-empty when visible; hidden + cleared when `plateOption` = `random` |

A muted helper line under `vin` lists the demo VINs that trigger the
rejection branches (`ROPDEMOFAILINSP01` — failed inspection,
`ROPDEMONOINSURE02` — uninsured, `ROPDEMOFINESDUE03` — outstanding fines).

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Submit application` | not submitting, all required fields valid | `applicantName:String, applicantEmail:String, civilId:String, residencyStatus:String, registrationType:String, vehicleCategory:String, vin:String, plateOption:String, reservedPlateNumber:String, sendBackReason="":String` |

## Send-back loop

`on-send-back: clear` — this form is the target of the traffic officer's
return-for-corrections loop. Reads `sendBackReason` from `data`, shows it
as a yellow banner above the form on resubmission, and clears it on the
next submit.

## Read-only mode

When `readOnly` is true (process finished), every input is `disabled`,
the fee preview still renders from the saved `vehicleCategory`, and the
action row is hidden.

## Notes

- The fee preview is the demo requirement "cost should be visible for the
  applicant at the beginning" — it must render before any field is filled
  (defaults to the default category's fee) and update on category change.
- `reservedPlateNumber` is submitted as an empty string when
  `plateOption` = `random` so the variable always exists (JUEL guards in
  later tasks stay simple).
- Civil-ID checksum and the real Omani plate taxonomy are out of POC
  scope; the form enforces shape only.
