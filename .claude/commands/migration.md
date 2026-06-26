# Create a Flyway migration file

Usage: `/migration <service-name> <short description>`

Example: `/migration inventory-svc add zone_id to batches`

Steps:
1. Find the existing migrations for the service:
   ```bash
   ls services/$SERVICE/src/main/resources/db/migration/
   ```
2. Determine the next version number (max existing V number + 1).
3. Create the file at `services/$SERVICE/src/main/resources/db/migration/V<N>__<slug>.sql`
   - Slug: lowercase, underscores, from the description.
   - File must be append-only DDL — no `DROP`, no `TRUNCATE` on non-empty tables.
   - Every new table must have `tenant_id UUID NOT NULL` + a composite index starting with `tenant_id` (unless it is an infrastructure table like `outbox`).
   - Money columns: `NUMERIC(18,4)` — never `FLOAT` or `DOUBLE`.
   - Timestamps: `TIMESTAMPTZ` — never `TIMESTAMP WITHOUT TIME ZONE`.

Parse `$ARGUMENTS`:
- `$ARGUMENTS` format: `<service-name> <description words...>`
- First word = service name (strip `-svc` suffix if needed to match directory name)
- Remaining words = migration description
