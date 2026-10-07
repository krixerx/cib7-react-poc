# Decision: `business-auto-approval`

**Decision id:** `business-auto-approval`
**BPMN business rule task:** `Task_AutoDecide`
**Output variable:** `autoDecision` (single entry)
**History TTL:** `P30D` (matches the BPMN's process TTL — required by CIB seven 2.2)

## Inputs

| Input | Type | Source variable |
|---|---|---|
| Applicant age | `integer` | `applicantAge` |
| Share capital | `double` | `shareCapital` |
| Applicant residency | `string` | `applicantResidency` |

## Output

| Output | Type | Name | Values |
|---|---|---|---|
| Decision | `string` | `autoDecision` | `"approve"` | `"review"` |

## Hit policy

`FIRST` — rules evaluated top-to-bottom; the first match wins; one row of
output is returned (mapped to `autoDecision` via `singleEntry`).

## Rules

| # | Rule id | Applicant age | Share capital | Residency | Decision |
|---|---|---|---|---|---|
| 0 | `Rule_DemoAlwaysReview` | `-` | `-` | `-` | `"review"` |
| 1 | `Rule_BelowMinimumCapital` | `-` | `< 2500` | `-` | `"review"` |
| 2 | `Rule_UnderageFounder` | `< 18` | `-` | `-` | `"review"` |
| 3 | `Rule_ForeignFounder` | `-` | `-` | `"foreign"` | `"review"` |
| 4 | `Rule_CitizenOrEResidentAdult` | `>= 18` | `>= 2500` | `"citizen", "e-resident"` | `"approve"` |
| 5 | `Rule_DefaultReview` | `-` | `-` | `-` | `"review"` |

**Demo mode:** rule 0 matches every case, so with hit policy `FIRST` every
registration currently goes to civil-servant review and rules 1 to 5 never
fire. It exists so the PartB review step can be shown in every demo. Delete
rule 0 to restore auto-approval; rules 1 to 5 are the real policy.

Rule 5 is the catch-all for anything rules 1 to 4 do not cover (an
unexpected or missing residency value). Without it, hit policy `FIRST`
yields an empty result for unmatched inputs instead of a decision.

## Why these rules

- **Share capital floor (Rule 1)** — historical Estonian Commercial Code
  required €2500 minimum for a private limited company. The POC keeps
  this floor for demo clarity. Real-world: relaxed in 2023.
- **Adult applicant (Rule 2)** — corporate-law signing capacity. Below 18
  always needs a guardian's countersignature and human review.
- **Foreign founder (Rule 3)** — KYB depth and statutory representation
  rules differ from Estonian residents, so a person always reviews.
- **Auto-approval (Rule 4)** — adult + sufficient capital. No upper bound
  in the POC; in production we'd cap at e.g. €25000 and require KYC review
  above that.
