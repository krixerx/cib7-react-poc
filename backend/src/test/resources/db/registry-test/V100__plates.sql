create table "reg_plates" (
  "plate" varchar(10) not null,
  "holder" varchar(100) not null,
  primary key ("plate")
);
insert into "reg_plates" ("plate", "holder") values ('123ABC', 'Homer');
