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

## Output

| Output | Type | Name | Values |
|---|---|---|---|
| Decision | `string` | `autoDecision` | `"approve"` | `"review"` |

## Hit policy

`FIRST` — rules evaluated top-to-bottom; the first match wins; one row of
output is returned (mapped to `autoDecision` via `singleEntry`).

## Rules

| # | Applicant age | Share capital | Decision |
|---|---|---|---|
| 1 | `-` | `< 2500` | `"review"` |
| 2 | `< 18` | `-` | `"review"` |
| 3 | `>= 18` | `>= 2500` | `"approve"` |

The default-fallthrough case (no rule matches, which can only happen for
malformed inputs like negative share capital) returns `null` —
`mapDecisionResult="singleEntry"` writes `null` into `autoDecision`, which
falls through to the gateway's default branch (manual review). The engine
never throws on no-match.

## Why these rules

- **Share capital floor (Rule 1)** — historical Estonian Commercial Code
  required €2500 minimum for a private limited company. The POC keeps
  this floor for demo clarity. Real-world: relaxed in 2023.
- **Adult applicant (Rule 2)** — corporate-law signing capacity. Below 18
  always needs a guardian's countersignature and human review.
- **Auto-approval (Rule 3)** — adult + sufficient capital. No upper bound
  in the POC; in production we'd cap at e.g. €25000 and require KYC review
  above that.
