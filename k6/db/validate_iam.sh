#!/usr/bin/env bash
# Look one user up by email after a run. Connection details as in validate_all.sh.
#
#   k6/db/validate_iam.sh someone@k6.shelfj.test
set -euo pipefail
cd "$(dirname "$0")/../.."

EMAIL=${1:-}
if [ -z "$EMAIL" ]; then
  echo "Usage: $0 <email>"
  exit 2
fi

env_value() { grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2-; }
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-$(env_value POSTGRES_HOST_PORT)}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-$(env_value POSTGRES_USER)}"
export PGUSER="${PGUSER:-shelfj}"
export PGPASSWORD="${PGPASSWORD:-$(env_value POSTGRES_PASSWORD)}"
export PGDATABASE="${PGDATABASE:-shelfj}"

psql -X -v email="$EMAIL" <<'SQL'
\x on
SELECT id, tenant_id, type, email, status, created_at FROM iam.users WHERE lower(email) = lower(:'email') LIMIT 1;
SQL
