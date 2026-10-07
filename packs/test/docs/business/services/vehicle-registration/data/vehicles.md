# Registry: `vehicles`

Stand-in for the Estonian vehicle registry (Liiklusregister): read-only
reference data with no owner or other personal data in it. The owner form
lists it in its vehicle dropdown, and `Task_GetPrice` looks one vehicle up by
VIN.

**Entity:** `vehicles`
**Table:** `reg_vehicles`
**Key:** `vin`
**List order:** `make`

## Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `vin` | string, max 17 | key | Realistic-looking VINs that decode to no real vehicle. |
| `make` | string, max 100 | yes | |
| `model` | string, max 100 | yes | |
| `year` | integer | yes | Model year. |
| `value` | number | yes | Value in EUR; drives the fee tier and the auto-approval DMN. |
| `fuelType` | string, max 20 | yes | |

## Derived fields

| Field | Rule | Notes |
|---|---|---|
| `ageYears` | years since `year` | Computed when read, never stored, so the BPMN needs no date arithmetic. |

## Operations

| Operation | Access | Notes |
|---|---|---|
| list | public | The owner form's dropdown. |
| lookup by `vin` | public | `Task_GetPrice` through the bus. A missing VIN answers 404, which raises an incident. |

`public` is allowed only because the data is harmless reference data
(docs/security.md rule 5).

## Seed

The span of values is intentional: it covers every fee tier (under €5k,
€20k, €50k and above) and every DMN path (under-age owner, luxury,
cheap-old auto-approve, default review).

| vin | make | model | year | value | fuelType |
|---|---|---|---|---|---|
| WVWZZZ1KZAW123001 | VW | Golf 1.4 TSI | 2018 | 8400 | Petrol |
| TMBJC23456789012X | Skoda | Octavia 1.5 TSI | 2020 | 14500 | Petrol |
| 5YJ3E1EA1JF000123 | Tesla | Model 3 Long Range | 2022 | 38000 | Electric |
| YV1UZA8VCK1234001 | Volvo | XC60 2.0 D4 | 2019 | 22000 | Diesel |
| WBA5R7C50KAA00789 | BMW | X3 xDrive20d | 2021 | 34000 | Diesel |
| WAUZZZ8K6HA001234 | Audi | A4 2.0 TDI | 2017 | 15500 | Diesel |
| JTNK4RBE60J123456 | Toyota | Corolla 1.8 Hybrid | 2023 | 19800 | Hybrid |
| VF15RBA0H55012345 | Renault | Clio 1.2 | 2015 | 4200 | Petrol |
| WDD2130421A123456 | Mercedes-Benz | E 220d | 2020 | 42500 | Diesel |
| WP0AB2A91KS123456 | Porsche | 911 Carrera | 2019 | 88000 | Petrol |
