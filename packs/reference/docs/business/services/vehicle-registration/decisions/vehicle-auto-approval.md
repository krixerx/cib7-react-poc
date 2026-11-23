# Decision: `vehicle-auto-approval`

**Decision id:** `vehicle-auto-approval`
**BPMN business rule task:** `Task_AutoDecide`
**Output variable:** `autoDecision` (single entry)
**History TTL:** `P30D` (matches the BPMN's process TTL; required by CIB seven 2.2)

## Inputs

| Input | Type | Source variable |
|---|---|---|
| Owner age | `integer` | `age` (owner form) |
| Vehicle value (EUR) | `double` | `price` (from [`look-up-vehicle`](../service-tasks/look-up-vehicle.md)) |
| Vehicle age (years) | `integer` | `vehicleAgeYears` (from [`look-up-vehicle`](../service-tasks/look-up-vehicle.md)) |

## Output

| Output | Type | Name | Values |
|---|---|---|---|
| Decision | `string` | `autoDecision` | `"approve"` \| `"review"` |

`"approve"` skips the Transport Authority review and goes straight to the
state fee invoice; `"review"` routes to `Task_Review`.

## Hit policy

`FIRST`: rules are evaluated top to bottom, the first match wins, and one
output row is returned (mapped to `autoDecision` via `singleEntry`).

## Rules

| # | Rule id | Owner age | Vehicle value | Vehicle age | Decision |
|---|---|---|---|---|---|
| 1 | `Rule_UnderageOwner` | `< 18` | `-` | `-` | `"review"` |
| 2 | `Rule_LuxuryVehicle` | `-` | `>= 50000` | `-` | `"review"` |
| 3 | `Rule_LowValueAdultOwner` | `>= 18` | `< 5000` | `>= 10` | `"approve"` |
| 4 | `Rule_DefaultReview` | `-` | `-` | `-` | `"review"` |

Rule 4 is the catch-all: without it, hit policy `FIRST` yields an empty
result for unmatched inputs instead of a decision.

## Examples

Run against the DMN by the core's pack checks (`DecisionExamplesTest`):
inputs by source variable, the expected output, values as JSON literals.

| age | price | vehicleAgeYears | autoDecision |
|---|---|---|---|
| `30` | `3000.0` | `12` | `"approve"` |
| `17` | `3000.0` | `12` | `"review"` |
| `40` | `50000.0` | `12` | `"review"` |
| `30` | `3000.0` | `5` | `"review"` |
| `30` | `5000.0` | `20` | `"review"` |

## Why these rules

- **Underage owner (rule 1):** a minor cannot be the sole registered owner,
  so the Transport Authority checks the ID flow.
- **Luxury vehicle (rule 2):** a vehicle worth EUR 50 000 or more is always
  reviewed.
- **Cheap, old, adult owner (rule 3):** the routine "cheap second car",
  where extra scrutiny adds no signal.
- **Everything else (rule 4):** mid-value or newer vehicles go to review, the
  safe side. A vehicle missing from the registry gets `price` 9999 from
  `look-up-vehicle` and lands here.
