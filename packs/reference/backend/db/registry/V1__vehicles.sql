-- Generated from docs/business/services/vehicle-registration/data/vehicles.md.
-- Do not hand-edit: change the spec and regenerate, which adds the next
-- V<n>__*.sql. Runs in the backend database's `registry` schema, with its own
-- Flyway history table, so pack and core migrations never share version
-- numbers. Identifiers are double-quoted lowercase: field names such as
-- `year` and `value` are reserved words in H2, and quoting keeps one
-- spelling for H2 and Postgres.

create table "reg_vehicles" (
  "vin" varchar(17) not null,
  "make" varchar(100) not null,
  "model" varchar(100) not null,
  "year" integer not null,
  "value" double precision not null,
  "fuel_type" varchar(20) not null,
  primary key ("vin")
);

insert into "reg_vehicles" ("vin", "make", "model", "year", "value", "fuel_type") values
  ('WVWZZZ1KZAW123001', 'VW', 'Golf 1.4 TSI', 2018, 8400, 'Petrol'),
  ('TMBJC23456789012X', 'Skoda', 'Octavia 1.5 TSI', 2020, 14500, 'Petrol'),
  ('5YJ3E1EA1JF000123', 'Tesla', 'Model 3 Long Range', 2022, 38000, 'Electric'),
  ('YV1UZA8VCK1234001', 'Volvo', 'XC60 2.0 D4', 2019, 22000, 'Diesel'),
  ('WBA5R7C50KAA00789', 'BMW', 'X3 xDrive20d', 2021, 34000, 'Diesel'),
  ('WAUZZZ8K6HA001234', 'Audi', 'A4 2.0 TDI', 2017, 15500, 'Diesel'),
  ('JTNK4RBE60J123456', 'Toyota', 'Corolla 1.8 Hybrid', 2023, 19800, 'Hybrid'),
  ('VF15RBA0H55012345', 'Renault', 'Clio 1.2', 2015, 4200, 'Petrol'),
  ('WDD2130421A123456', 'Mercedes-Benz', 'E 220d', 2020, 42500, 'Diesel'),
  ('WP0AB2A91KS123456', 'Porsche', '911 Carrera', 2019, 88000, 'Petrol');
