#!/usr/bin/env bash
set -euo pipefail

PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5432}
PGUSER=${PGUSER:-shelfj}
PGDATABASE=${PGDATABASE:-shelfj}

echo "Running IAM validator (list recent users)"
psql "host=$PGHOST port=$PGPORT user=$PGUSER dbname=$PGDATABASE" -c "SELECT id, email, created_at FROM iam.users ORDER BY created_at DESC LIMIT 10;"

echo "Running product validator (recent products)"
psql "host=$PGHOST port=$PGPORT user=$PGUSER dbname=$PGDATABASE" -c "SELECT id, name, created_at FROM product.products ORDER BY created_at DESC LIMIT 10;" || true

echo "Running inventory validator (recent batches)"
psql "host=$PGHOST port=$PGPORT user=$PGUSER dbname=$PGDATABASE" -c "SELECT id, sku, quantity, created_at FROM inventory.inventory_batches ORDER BY created_at DESC LIMIT 10;" || true

echo "Running tenant validator (recent tenants)"
psql "host=$PGHOST port=$PGPORT user=$PGUSER dbname=$PGDATABASE" -c "SELECT id, name, created_at FROM tenant.tenants ORDER BY created_at DESC LIMIT 10;" || true

echo "Running sample validator (recent widgets)"
psql "host=$PGHOST port=$PGPORT user=$PGUSER dbname=$PGDATABASE" -c "SELECT id, name, created_at FROM sample.widgets ORDER BY created_at DESC LIMIT 10;" || true
