# Decision: `rop-vehicle-fee`

**Decision id:** `rop-vehicle-fee`
**BPMN business rule task:** `Task_RopVehicleFee`
**Output variable:** `registrationFee` (single entry)
**History TTL:** `P30D` (matches the BPMN's process TTL)

The authoritative fee computation — the demo document's "Service Fee"
table (amounts in OMR). The applicant form shows the same table as a live
preview before submitting; this DMN is what the payment step actually
charges.

## Inputs

| Input | Type | Source variable |
|---|---|---|
| Vehicle category | `string` | `vehicleCategory` |

## Output

| Output | Type | Name | Values |
|---|---|---|---|
| Registration fee | `double` | `registrationFee` | OMR amount |

## Hit policy

`FIRST` — the category select in the form restricts input to the twelve
known values; the catch-all keeps the decision total anyway.

## Rules

| # | Vehicle category | Fee (OMR) |
|---|---|---|
| 1 | `"motorcycle"` | `10` |
| 2 | `"private-lt1500"` | `15` |
| 3 | `"private-1500-3000"` | `20` |
| 4 | `"private-3000-4500"` | `30` |
| 5 | `"private-gt4500"` | `50` |
| 6 | `"electric-lt75kw"` | `15` |
| 7 | `"electric-75-150kw"` | `20` |
| 8 | `"electric-150-224kw"` | `30` |
| 9 | `"electric-gt224kw"` | `50` |
| 10 | `"tractor"` | `40` |
| 11 | `"truck-3-5t"` | `120` |
| 12 | `"truck-gte5t"` | `180` |
| 13 | `-` | `15` |

## Why these rules

Rows 1–12 are verbatim from the demo document's fee table (motorcycle 10;
engine-capacity tiers 15/20/30/50; electric-power tiers 15/20/30/50;
tractor 40; trucks 120/180). The "truck under 3 tons — according to engine
capacity" row of the source table is intentionally folded into the
engine-capacity tiers. Row 13 is a defensive catch-all (smallest private
fee) so a malformed category can never produce a `null` fee and break the
payment page.
