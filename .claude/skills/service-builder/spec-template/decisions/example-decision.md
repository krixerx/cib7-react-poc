<!--
  DMN spec — one file per business rule task. Filename = decision id
  (kebab-case, must match camunda:decisionRef in the BPMN).

  The service-builder skill emits a standalone DMN at
  cib7/src/main/resources/processes/<decision-id>.dmn with the rule rows
  below as a decision table. Every emitted DMN carries
  camunda:historyTimeToLive (CIB seven 2.1 enforces it).

  Replace ALL `<…>` placeholders.
-->

# Decision: `<decision-id>`

**Decision id:** `<decision-id>` (kebab-case, globally unique; the BPMN's
  `camunda:decisionRef` must match this exactly)
**Display name:** `<Name shown in Cockpit>`
**Hit policy:** `FIRST` | `UNIQUE` | `COLLECT`
**History TTL:** `P30D` (must match the BPMN's process-level TTL)

## Inputs

| Variable | DMN type | Label |
|---|---|---|
| `<varName>` | `integer` | `<UI label>` |
| `<varName>` | `double` | `<UI label>` |
| `<varName>` | `string` | `<UI label>` |
| `<varName>` | `boolean` | `<UI label>` |

DMN types: `string`, `integer`, `long`, `double`, `boolean`, `date`.

## Outputs

Single-entry decisions (one output column) are the default — the BPMN reads
the value via `camunda:mapDecisionResult="singleEntry"` into one variable.

| Output variable | DMN type | Label |
|---|---|---|
| `<outputVar>` | `string` | `<UI label>` |

## Rules

One row per rule. Hit policy `FIRST` means rules are evaluated top-down; the
first match wins. Use `-` for "any value".

FEEL syntax in input entries: `< 18`, `>= 100`, `[18..65]`, `"approve"`, `-`.
Output entries are quoted FEEL literals: `"approve"`, `100`, `true`.

| Rule id | `<input-1>` | `<input-2>` | … | `<outputVar>` |
|---|---|---|---|---|
| `Rule_<Name>` | `< 18` | `-` | … | `"review"` |
| `Rule_<Name>` | `>= 18` | `< 100` | … | `"approve"` |
| `Rule_<Default>` | `-` | `-` | … | `"review"` |

## Notes

<Why these rules in this order, edge cases that aren't covered, links to the
policy document that drives the decision.>
