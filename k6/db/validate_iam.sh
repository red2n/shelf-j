#!/usr/bin/env bash
set -euo pipefail

EMAIL=${1:-}
if [ -z "$EMAIL" ]; then
  echo "Usage: $0 <email>"
  exit 2
fi

PGHOST=${PGHOST:-localhost}
PGPORT=${PGPORT:-5432}
PGUSER=${PGUSER:-shelfj}
PGDATABASE=${PGDATABASE:-shelfj}

psql "host=$PGHOST port=$PGPORT user=$PGUSER dbname=$PGDATABASE" -v email="$EMAIL" -c "\
\x on
SELECT id, email, created_at FROM iam.users WHERE email = :'email' LIMIT 1;"
