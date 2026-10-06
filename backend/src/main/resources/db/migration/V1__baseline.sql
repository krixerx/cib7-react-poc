-- Baseline of the backend's own tables, as Hibernate generated them from the
-- entities (CaseCard, Document, PaymentSession) for both PostgreSQL and H2.
-- Flyway owns the schema from here on; Hibernate only validates it
-- (spring.jpa.hibernate.ddl-auto=validate), so an entity change needs a new
-- V<n>__*.sql next to this file.

create table case_cards (
  process_instance_id varchar(255) not null,
  service varchar(255) not null,
  status varchar(255) not null,
  summary varchar(4000) not null,
  updated_at timestamp(6) with time zone not null,
  primary key (process_instance_id)
);

create table documents (
  id varchar(255) not null,
  process_instance_id varchar(255) not null,
  category varchar(255) not null,
  filename varchar(255) not null,
  content_type varchar(255) not null,
  s3_key varchar(1024) not null,
  uploader_user_id varchar(255),
  created_at timestamp(6) with time zone not null,
  primary key (id)
);

create index idx_documents_pi on documents (process_instance_id);

create table payment_sessions (
  id varchar(255) not null,
  process_instance_id varchar(255) not null,
  process_definition_key varchar(255) not null,
  service_name varchar(255) not null,
  recipient varchar(255) not null,
  amount numeric(12,2) not null,
  currency varchar(3) not null,
  reference varchar(255) not null,
  status varchar(16) not null,
  created_at timestamp(6) with time zone not null,
  paid_at timestamp(6) with time zone,
  version bigint not null,
  primary key (id)
);

create index idx_payment_sessions_pi on payment_sessions (process_instance_id);
