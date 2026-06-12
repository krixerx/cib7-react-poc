# Form: `rop-permit-application`

**Form id:** `rop-permit-application` (kebab-case, globally unique)
**BPMN task:** `Task_RopPermitApplication`
**Audience:** `initiator`
**Mode:** `entry`

## Intro

"Apply for a driving learning license from the General Traffic Department
(Royal Oman Police). **Service fee: 6 OMR** — payable after your
application is approved."

The fee line is rendered as a highlighted badge at the top of the form —
the cost is visible before any field is filled (flat fee per the demo
document, so no live recalculation is needed).

## Fields

| Field name | UI label | Input type | Required | Default (from variable) | Validation |
|---|---|---|---|---|---|
| `applicantName` | `Full name` | `text` | yes | `data.applicantName` | non-empty, trim |
| `applicantEmail` | `Email address` | `email` | yes | `data.applicantEmail` | non-empty, must contain `@` |
| `civilId` | `Civil number` | `text` | yes | `data.civilId` | exactly 8 digits |
| `age` | `Age (years)` | `number` | yes | `data.age` | integer 15..100 (the DMN, not the form, enforces the legal minimums so the rejection branch is demoable) |
| `residencyStatus` | `Residency status` | `select` (`citizen` Omani citizen \| `gcc-citizen` GCC-country citizen \| `resident` Resident) | yes | `data.residencyStatus` (default `citizen`) | one of the three values |
| `hasResidentCard` | `I hold a valid resident card` | `checkbox` | no | `data.hasResidentCard` (default `false`) | — shown with a helper "Required for GCC-country citizens" |
| `licenseCategory` | `License category` | `select` (`light-vehicle` Light vehicle \| `motorcycle` Motorcycle \| `heavy-vehicle` Heavy vehicle \| `mechanical-equipment` Mechanical equipment) | yes | `data.licenseCategory` (default `light-vehicle`) | one of the four values; helper "Heavy vehicle and mechanical equipment require age 21+" |
| `specialNeeds` | `I am a person with special needs` | `checkbox` | no | `data.specialNeeds` (default `false`) | — |
| `profession` | `Profession (residents)` | `text` | no | `data.profession` | optional; helper "For residents, the license category must match the profession registered with the Ministry of Labor" |

A muted helper line under `civilId` lists the demo civil IDs:
`90000001` weak vision → Police Hospital branch, `90000002` failed eye
test, `90000003` no eye test on file, `90000004` already holds a valid
license, `90000005` outstanding restrictions; any other 8-digit number is
all-clear.

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Submit application` | not submitting, all required fields valid | `applicantName:String, applicantEmail:String, civilId:String, age:Integer, residencyStatus:String, hasResidentCard:Boolean, licenseCategory:String, specialNeeds:Boolean, profession:String` |

`profession` is submitted as an empty string when blank so later JUEL /
FreeMarker guards stay simple.

## Send-back loop

`on-send-back: none` — this service has no send-back loop; rejected
applicants start a new case.

## Read-only mode

When `readOnly` is true every input is `disabled`, the fee badge still
renders, and the action row is hidden.

## Notes

- The eye test is NOT a form field — the demo's terms require the result
  to come electronically from approved opticians, which the
  `rop-driver-clearance` service task fetches by `civilId` right after
  submission.
