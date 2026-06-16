# Decision: `transport-permit-eligibility`

**Decision id:** `transport-permit-eligibility`
**BPMN business rule task:** `Task_TransportPermitEligibility`
**Output variable:** `permitDecision` (single entry)
**History TTL:** `P30D` (matches the BPMN's process TTL)

Implements the "Terms of Service" list of Transport Authority Demo Scenario 2 — *"The
system checks that the conditions are met. If the conditions are not met,
a rejection notice is sent to the service applicant, along with the
reasons for the rejection."*

## Inputs

| Input | Type | Source variable |
|---|---|---|
| Restrictions cleared | `boolean` | `restrictionsCleared` |
| Has valid temporary license | `boolean` | `hasValidTemporaryLicense` |
| Age | `integer` | `age` |
| License category | `string` | `licenseCategory` |
| Special needs | `boolean` | `specialNeeds` |
| Residency status | `string` | `residencyStatus` |
| Has resident card | `boolean` | `hasResidentCard` |
| Eye test result | `string` | `eyeTestResult` |

## Output

| Output | Type | Name | Values |
|---|---|---|---|
| Permit decision | `string` | `permitDecision` | `"ok"`, `"medical"`, or a human-readable rejection sentence |

`"medical"` routes to the Police Hospital branch; any value that is
neither `"ok"` nor `"medical"` is the rejection reason rendered verbatim
in the rejection notice (unless the hospital later writes
`rejectionReason`).

## Hit policy

`FIRST` — top-to-bottom, first match wins, `singleEntry` →
`permitDecision`.

## Rules

| # | Restrictions cleared | Has valid temp. license | Age | License category | Special needs | Residency status | Has resident card | Eye test result | Permit decision |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `false` | `-` | `-` | `-` | `-` | `-` | `-` | `-` | `"Outstanding traffic restrictions (violations or circulars) must be cleared first."` |
| 2 | `-` | `true` | `-` | `-` | `-` | `-` | `-` | `-` | `"You already hold a valid (temporary) driving license; a new learning permit cannot be issued."` |
| 3 | `-` | `-` | `< 18` | `-` | `-` | `-` | `-` | `-` | `"The legal minimum age for a learning permit is 18 years."` |
| 4 | `-` | `-` | `< 21` | `"heavy-vehicle","mechanical-equipment"` | `-` | `-` | `-` | `-` | `"The legal minimum age for heavy vehicle and mechanical equipment licenses is 21 years."` |
| 5 | `-` | `-` | `-` | `"heavy-vehicle","mechanical-equipment","motorcycle"` | `true` | `-` | `-` | `-` | `"Persons with special needs are not eligible for mechanical equipment, heavy vehicle, or motorcycle licenses."` |
| 6 | `-` | `-` | `-` | `-` | `-` | `"gcc-citizen"` | `false` | `-` | `"Citizens of Gulf Cooperation Council countries are required to hold a resident card."` |
| 7 | `-` | `-` | `-` | `-` | `-` | `-` | `-` | `"fail"` | `"The eye test result from the approved optician does not meet the vision requirements."` |
| 8 | `-` | `-` | `-` | `-` | `-` | `-` | `-` | `"missing"` | `"No eye test result from an approved optician is on file. Please visit an approved optical shop first."` |
| 9 | `-` | `-` | `-` | `-` | `-` | `-` | `-` | `"weak"` | `"medical"` |
| 10 | `-` | `-` | `-` | `-` | `-` | `-` | `-` | `-` | `"ok"` |

Rule 10 is the explicit catch-all so the gateway conditions
(`== "ok"`, `== "medical"`) are always evaluated against a non-null
value.

## Why these rules

- **Rule 1** — demo: "Checking restrictions (violations, circulars)".
- **Rule 2** — demo: "Not having a new (temporary) valid driving license".
- **Rules 3–4** — demo: legal age 18; mechanical equipment and heavy
  vehicles 21.
- **Rule 5** — demo: "People with special needs are not entitled to a
  license to drive mechanical equipment, heavy vehicles and bicycles"
  (mapped to the `motorcycle` category).
- **Rule 6** — demo: "Citizens of the Gulf Cooperation Council countries
  are required to obtain a resident card".
- **Rules 7–9** — demo: "Pass the eye test (verify the eye test data
  electronically by linking with approved opticians)"; weak vision routes
  to the Police Hospital instead of rejecting.
- The demo's profession-based category matching for foreigners (Ministry
  of Labor) is informational in the POC — listed in the README's Known
  trade-offs.
