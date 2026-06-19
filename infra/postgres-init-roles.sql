-- Per-service Postgres roles: each business service connects as its own role, granted
-- privileges on only its own schema. Closes the gap where every service shared one
-- (superuser) role and DB-level isolation depended solely on app-level convention +
-- the default search_path (golden rule #1 — database-per-service).
--
-- Idempotent: safe to re-run against either a fresh or an already-populated database.
-- Run as the bootstrap superuser (POSTGRES_USER, default "shelfj"):
--   docker compose exec -T postgres psql -U shelfj -d shelfj -f - < infra/postgres-init-roles.sql
-- (Also mounted into docker-entrypoint-initdb.d for fresh volumes — see docker-compose.yml.)
--
-- Dev-only passwords below. Production sets each via <SERVICE>_DB_PASSWORD (.env.example)
-- and must ALTER ROLE ... PASSWORD with a generated secret before going live.

\set ON_ERROR_STOP on

CREATE OR REPLACE FUNCTION pg_temp.shelfj_provision_service_role(
  role_name text, schema_name text, role_password text
) RETURNS void AS $fn$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = role_name) THEN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', role_name, role_password);
  END IF;
  EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', schema_name);
  EXECUTE format('GRANT CONNECT ON DATABASE shelfj TO %I', role_name);
  EXECUTE format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', schema_name, role_name);
  -- Covers tables/sequences that already exist in the schema (e.g. created by the
  -- bootstrap role before this script ran); going forward the role owns what it creates.
  EXECUTE format('GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I TO %I', schema_name, role_name);
  EXECUTE format(
    'GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I TO %I', schema_name, role_name);
  EXECUTE format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES TO %I', schema_name, role_name);
  EXECUTE format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO %I', schema_name, role_name);
END;
$fn$ LANGUAGE plpgsql;

SELECT pg_temp.shelfj_provision_service_role('iam_svc', 'iam', 'iam_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('tenant_svc', 'tenant', 'tenant_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('product_svc', 'product', 'product_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role(
  'inventory_svc', 'inventory', 'inventory_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('purchase_svc', 'purchase', 'purchase_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('pricing_svc', 'pricing', 'pricing_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('cart_svc', 'cart', 'cart_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('order_svc', 'order', 'order_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('payment_svc', 'payment', 'payment_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role('customer_svc', 'customer', 'customer_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role(
  'notification_svc', 'notification', 'notification_dev_change_me');
SELECT pg_temp.shelfj_provision_service_role(
  'reporting_svc', 'reporting', 'reporting_dev_change_me');

DROP FUNCTION pg_temp.shelfj_provision_service_role(text, text, text);
