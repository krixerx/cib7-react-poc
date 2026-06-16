# Form: `transport-hospital-assessment`

**Form id:** `transport-hospital-assessment` (kebab-case, globally unique)
**BPMN task:** `Task_TransportHospitalAssessment`
**Audience:** `civil-servant` group (plays the Police Hospital medical board)
**Mode:** `review` (read-only applicant data + assessment input)

## Intro

"The approved optician reported **weak vision** for this applicant. Record
the Police Hospital's medical assessment of their eligibility for a
driving license." (Demo: *"After conducting the medical examination for
those with weak vision, the result is sent to the integrated traffic
system."*)

## Read-only display

Definition list from `data`:

| Label | Variable |
|---|---|
| Applicant | `applicantName` |
| Civil number | `civilId` |
| Age | `age` |
| License category | `licenseCategory` |
| Special needs | `specialNeeds` (yes / no) |
| Eye test result | `eyeTestResult` (renders as "weak vision" badge) |

## Fields (assessor input)

| Field name | UI label | Input type | Required | Default | Validation |
|---|---|---|---|---|---|
| `medicalResult` | `Assessment result` | `radio` (`positive` Fit to drive — proceed \| `negative` Not fit to drive — reject) | yes | none selected | one of the two values |
| `medicalNotes` | `Assessment notes` | `textarea` | only when `negative` | empty | non-empty when `negative` |

## Actions

| Button label | When enabled | complete-with |
|---|---|---|
| `Submit assessment` | a result is selected; notes present if negative | `medicalResult:String, medicalNotes:String, rejectionReason:String` |

`rejectionReason` is computed by the form: empty string when `positive`;
when `negative` it is `"Police Hospital medical assessment: " +
medicalNotes` — the shared rejection-email template prefers
`rejectionReason` over the DMN's `permitDecision` text, so the applicant
sees the hospital's wording.

## Send-back loop

`on-send-back: none`.

## Read-only mode

When `readOnly` is true the radio + textarea are `disabled` and the
recorded result renders as text.

## Notes

- This task is the POC stand-in for the demo's automatic electronic
  linkage between ITS and the Police Hospital system — see the README's
  Known trade-offs.
