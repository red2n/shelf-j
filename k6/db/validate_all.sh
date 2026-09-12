#!/usr/bin/env bash
# Post-run database checks that k6 cannot make itself: what the suites wrote, and that every id
# in the database is a UUIDv7. Connection details come from the environment, then .env, then the
# docker-compose defaults. Exits non-zero if any stored id is not v7.
#
#   k6/db/validate_all.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

env_value() { grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2-; }
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-$(env_value POSTGRES_HOST_PORT)}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-$(env_value POSTGRES_USER)}"
export PGUSER="${PGUSER:-shelfj}"
export PGPASSWORD="${PGPASSWORD:-$(env_value POSTGRES_PASSWORD)}"
export PGDATABASE="${PGDATABASE:-shelfj}"

q() { psql -X -v ON_ERROR_STOP=1 -c "$1"; }

echo "── recent users"
q "SELECT id, email, type, created_at FROM iam.users WHERE created_at > now() - interval '1 day' ORDER BY created_at DESC LIMIT 10;"
echo "── recent tenants"
q "SELECT id, name, status, currency, created_at FROM tenant.tenants WHERE created_at > now() - interval '1 day' ORDER BY created_at DESC LIMIT 10;"
echo "── recent products"
q "SELECT id, tenant_id, name, created_at FROM product.products WHERE created_at > now() - interval '1 day' ORDER BY created_at DESC LIMIT 10;"
echo "── recent stock batches"
q "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty, remaining_qty, material_status, created_at FROM inventory.inventory_batches WHERE created_at > now() - interval '1 day' ORDER BY created_at DESC LIMIT 10;"

echo "── every stored id is UUIDv7"
# Every uuid column of every table in the service schemas, counted by version character.
report=$(psql -X -q -v ON_ERROR_STOP=1 2>&1 <<'SQL'
DO $$
DECLARE r record; n bigint;
BEGIN
  FOR r IN SELECT c.table_schema, c.table_name, c.column_name
           FROM information_schema.columns c
           JOIN information_schema.tables t USING (table_schema, table_name)
           WHERE c.data_type = 'uuid' AND t.table_type = 'BASE TABLE'
             AND c.table_schema NOT IN ('pg_catalog', 'information_schema', 'public')
  LOOP
    EXECUTE format('SELECT count(*) FROM %I.%I WHERE %I IS NOT NULL AND substr(%I::text, 15, 1) <> %L',
                   r.table_schema, r.table_name, r.column_name, r.column_name, '7') INTO n;
    IF n > 0 THEN
      RAISE NOTICE '%.%.% has % non-v7 ids', r.table_schema, r.table_name, r.column_name, n;
    END IF;
  END LOOP;
END $$;
SQL
)
if grep -q 'non-v7' <<<"$report"; then
  grep 'non-v7' <<<"$report" | sed 's/^.*NOTICE: */  /'
  exit 1
fi
echo "all ids are v7"
