#!/usr/bin/env bash
# Restore rehearsal (readiness review 21.14): back the stack's Postgres up, restore the dump into a
# fresh Postgres 16 container, re-apply the per-service roles, and prove every table came back row for
# row — timed, so the restore time promised is one that was measured.
#
# The dump and the row counts it is checked against are taken inside one exported snapshot, so writes
# the running stack makes meanwhile cannot make a good restore look wrong, or a bad one look right.
#
# Usage: scripts/restore-rehearsal.sh [report.md]
#   SHELFJ_PG_CONTAINER (shelfj-postgres), SHELFJ_PG_DB (shelfj), SHELFJ_PG_USER (shelfj)
# Appends a dated entry to the report (default docs/RESTORE-REHEARSAL.md); exits 1 on any mismatch.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${SHELFJ_PG_CONTAINER:-shelfj-postgres}"
DB="${SHELFJ_PG_DB:-shelfj}"
PGUSER_="${SHELFJ_PG_USER:-shelfj}"
REPORT="${1:-docs/RESTORE-REHEARSAL.md}"
SCRATCH="shelfj-restore-rehearsal"
IMAGE="postgres:16-alpine"
WORK="$(mktemp -d)"

cleanup() {
  docker rm -f "$SCRATCH" >/dev/null 2>&1 || true
  docker exec "$SRC" rm -f /tmp/rehearsal.dump /tmp/rehearsal-source.txt >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

now() { date +%s.%N; }
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }

COUNTS="SELECT table_schema || '.' || table_name || '|' ||
  (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', table_schema, table_name),
    false, true, '')))[1]::text
  FROM information_schema.tables
  WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
  ORDER BY 1"

echo "restore rehearsal: $SRC/$DB -> $SCRATCH ($IMAGE)"
t_start=$(now)

# 1. One snapshot: export it from a session held open, dump it, count it, then let it go.
coproc SNAP { docker exec -i "$SRC" psql -U "$PGUSER_" -d "$DB" -At -q; }
echo "BEGIN ISOLATION LEVEL REPEATABLE READ; SELECT pg_export_snapshot();" >&"${SNAP[1]}"
# psql may echo command tags before the result; read until the snapshot id arrives.
snapshot=""
while read -r line <&"${SNAP[0]}"; do
  if [[ "$line" =~ ^[0-9A-F]+-[0-9A-F]+-[0-9]+$ ]]; then snapshot="$line"; break; fi
done
[ -n "$snapshot" ] || { echo "no snapshot exported" >&2; exit 1; }
docker exec "$SRC" pg_dump -U "$PGUSER_" -d "$DB" -Fc --snapshot="$snapshot" -f /tmp/rehearsal.dump
t_dumped=$(now)
{
  echo "\\o /tmp/rehearsal-source.txt"
  echo "$COUNTS;"
  echo "\\o"
  echo "SELECT 'counted';"
} >&"${SNAP[1]}"
counted=""
while read -r line <&"${SNAP[0]}"; do
  if [ "$line" = "counted" ]; then counted=yes; break; fi
done
[ -n "$counted" ] || { echo "the snapshot session never finished counting" >&2; exit 1; }
echo "COMMIT;" >&"${SNAP[1]}"
snap_in="${SNAP[1]}"
eval "exec ${snap_in}>&-"
wait "$SNAP_PID" || true
docker cp "$SRC":/tmp/rehearsal-source.txt "$WORK/source.txt"
docker cp "$SRC":/tmp/rehearsal.dump "$WORK/shelfj.dump"
dump_bytes=$(stat -c %s "$WORK/shelfj.dump")
t_copied=$(now)

# 2. A fresh server, the dump restored into it, and the roles every service connects as.
docker rm -f "$SCRATCH" >/dev/null 2>&1 || true
docker run -d --name "$SCRATCH" -e POSTGRES_USER="$PGUSER_" -e POSTGRES_PASSWORD=rehearsal -e POSTGRES_DB="$DB" "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$SCRATCH" pg_isready -U "$PGUSER_" -d "$DB" >/dev/null 2>&1 && break
  sleep 1
done
t_ready=$(now)
docker cp "$WORK/shelfj.dump" "$SCRATCH":/tmp/shelfj.dump
docker exec "$SCRATCH" pg_restore -U "$PGUSER_" -d "$DB" --no-owner --no-privileges -j 4 /tmp/shelfj.dump
t_restored=$(now)
docker exec -i "$SCRATCH" psql -U "$PGUSER_" -d "$DB" -q -v ON_ERROR_STOP=1 < infra/postgres-init-roles.sql >/dev/null
t_roles=$(now)

# 3. Every table, row for row.
docker exec "$SCRATCH" psql -U "$PGUSER_" -d "$DB" -At -c "$COUNTS" > "$WORK/restored.txt"
t_verified=$(now)
tables=$(wc -l < "$WORK/source.txt")
rows=$(awk -F'|' '{ n += $2 } END { print n + 0 }' "$WORK/source.txt")
mismatches=$(diff "$WORK/source.txt" "$WORK/restored.txt" || true)
pg_version=$(docker exec "$SCRATCH" psql -U "$PGUSER_" -d "$DB" -At -c "SHOW server_version")

dump_s=$(secs "$t_start" "$t_dumped")
copy_s=$(secs "$t_dumped" "$t_copied")
start_s=$(secs "$t_copied" "$t_ready")
restore_s=$(secs "$t_ready" "$t_restored")
roles_s=$(secs "$t_restored" "$t_roles")
verify_s=$(secs "$t_roles" "$t_verified")
total_s=$(secs "$t_start" "$t_verified")
result=$([ -z "$mismatches" ] && echo "every table matched" || echo "MISMATCHES")

echo "tables $tables, rows $rows, dump $dump_bytes bytes"
echo "dump ${dump_s}s, copy ${copy_s}s, server start ${start_s}s, restore ${restore_s}s, roles ${roles_s}s, verify ${verify_s}s: total ${total_s}s — $result"

{
  [ -s "$REPORT" ] || printf '# Restore rehearsals\n\nEach entry is appended by `scripts/restore-rehearsal.sh`: the stack'"'"'s Postgres dumped from one snapshot, restored into a fresh %s container with the per-service roles re-applied, and every table compared row for row against counts taken in the same snapshot.\n' "$IMAGE"
  printf '\n## %s\n\n' "$(date -u +'%Y-%m-%d %H:%M UTC')"
  printf -- '- Source: `%s`, database `%s`; restored on PostgreSQL %s\n' "$SRC" "$DB" "$pg_version"
  printf -- '- %s tables, %s rows, a %s-byte custom-format dump\n' "$tables" "$rows" "$dump_bytes"
  printf -- '- Dump %ss, copy %ss, fresh server up %ss, restore %ss, roles %ss, verify %ss: **%ss in all**\n' "$dump_s" "$copy_s" "$start_s" "$restore_s" "$roles_s" "$verify_s" "$total_s"
  printf -- '- Result: %s\n' "$result"
  if [ -n "$mismatches" ]; then printf '\n```\n%s\n```\n' "$mismatches"; fi
} >> "$REPORT"

[ -z "$mismatches" ]
