This folder contains SQL validation scripts and small shell runners that you can use after running the k6 API tests.

Usage
- Set these environment variables or export them before running the scripts:
  - `PGHOST` (default: localhost)
  - `PGPORT` (default: 5432)
  - `PGUSER` (default: shelfj)
  - `PGDATABASE` (default: shelfj)
  - `PGPASSWORD` (if required)

- Example: validate IAM user created by the IAM k6 test
```bash
export PGHOST=localhost
export PGPORT=5432
export PGUSER=shelfj
export PGDATABASE=shelfj
./validate_iam.sh "k6-iam-<timestamp>@example.com"
```

General runner
- `validate_all.sh` will execute all validators in this folder (best-effort).
