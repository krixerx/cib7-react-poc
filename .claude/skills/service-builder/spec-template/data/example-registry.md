<!--
  Registry spec — one file per registry entity the service needs (reference
  data, lookups the engine makes). The service-builder skill (step 10b) emits:
    packs/reference/backend/registry/<entity>.yaml        (descriptor)
    packs/reference/backend/db/registry/V<n>__<entity>.sql (table + seed)
  The backend's registry module serves it; no Java is written per registry.

  Replace ALL `<…>` placeholders. Only the types, operations, access levels
  and derived rules shown here exist; anything else is a spec gap.
-->

# Registry: `<entity>`

<What the data is, where the real system would be, who reads it.>

**Entity:** `<entity>` (URL segment: `/api/<public|internal>/registry/<entity>`)
**Table:** `reg_<name>`
**Key:** `<field>`
**List order:** `<field>` (optional; default the key)

## Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `<field>` | string, max N | key | |
| `<field>` | integer | yes | |
| `<field>` | number | yes | |
| `<field>` | boolean | no | |

## Derived fields (optional)

| Field | Rule | Notes |
|---|---|---|
| `<field>` | years since `<integer field>` | Computed when read, never stored. |

## Operations

| Operation | Access | Notes |
|---|---|---|
| list | public \| internal | |
| lookup by `<key>` | public \| internal | A missing key answers 404. |

`public` only for harmless reference data with nothing about a person in it
(docs/security.md rule 5); everything else is `internal`, which only the
engine reaches through the bus.

## Seed

| <field> | <field> | … |
|---|---|---|
| … | … | … |

## Changes

<Only for a change to an existing registry: what to rename, drop or retype,
and that the data loss is accepted. Without it the builder stops and asks.>
