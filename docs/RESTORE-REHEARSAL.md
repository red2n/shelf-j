# Restore rehearsals

Each entry is appended by `scripts/restore-rehearsal.sh`: the stack's Postgres dumped from one snapshot, restored into a fresh postgres:16-alpine container with the per-service roles re-applied, and every table compared row for row against counts taken in the same snapshot.

## 2026-09-15 11:24 UTC

- Source: `storeql-postgres`, database `storeql`; restored on PostgreSQL 16.15
- 255 tables, 91031 rows, a 5691630-byte custom-format dump
- Dump 1.2s, copy 0.4s, fresh server up 2.7s, restore 3.2s, roles 0.2s, verify 0.3s: **7.9s in all**
- Result: every table matched
