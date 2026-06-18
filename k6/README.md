# k6 Test Suite

This folder contains k6 test cases for realtime gateway validation, including load, stress, integration, and brute-force scenarios.

## Contents


DB validation
-------------

This `k6/db` folder contains SQL and shell scripts you can use to validate the database after running k6 tests. Because `k6` runs inside a JS sandbox it does not execute psql directly; instead the pattern used here is:

- Run the API tests with `k6` to exercise the HTTP endpoints.
- Run the validation shell scripts in `k6/db/` which invoke `psql` against the running Postgres container to assert the expected rows exist.

Examples
--------

Run the IAM CRUD test and then validate that the user exists:

```bash
# run the k6 test (creates a single user)
k6 run k6/iam-crud.js --env BASE_URL=http://localhost:8090

# validate the user list (best-effort)
export PGHOST=localhost
export PGPORT=5432
export PGUSER=shelfj
export PGDATABASE=shelfj
./k6/db/validate_all.sh
```

If you want a targeted check for the IAM user created by `iam-crud.js`, copy the email printed/logged by the k6 run and pass it to `k6/db/validate_iam.sh`.

Notes
-----
- The validation scripts are intentionally tolerant: they attempt queries in the service schema and will continue if a schema/table is not present. Adjust queries to fit your local schema names if you changed `shelfj.db.schema` values.
- For CI, you can wire the validation scripts as shell steps after `k6` runs; for robust assertions integrate a test-harness that runs SQL checks programmatically.
## Quick Start: Flow Guard Test

**New:** Flow guard comprehensive test validates all endpoints in proper business sequence:

```bash
# Run the complete flow guard test (tests 47 endpoints in happy-path sequence)
k6 run k6/flow-guard-comprehensive.js --env BASE_URL=http://localhost:8090
```

See [FLOW_GUARD_TEST_GUIDE.md](./FLOW_GUARD_TEST_GUIDE.md) for detailed coverage, [ENDPOINT_COVERAGE_AUDIT.md](./ENDPOINT_COVERAGE_AUDIT.md) for what's tested/deferred.

## Run other tests

Install `k6` first, then run one of the scripts:

```bash
# Gateway security tests
k6 run k6/gateway-smoke-it.js
k6 run k6/gateway-login-protection.js
k6 run k6/gateway-rate-limit-stress.js

# Full-stack concurrent workload simulation
k6 run k6/full-stack-simulation.js

# Service-specific CRUD tests
k6 run k6/tenant-crud.js
k6 run k6/product-crud.js
k6 run k6/inventory-crud.js
```

## Environment variables

- `BASE_URL`: gateway base URL (default: `http://localhost:8080`)
- `LOGIN_PATH`: login endpoint path (default: `/api/iam-svc/auth/login`)
- `INVALID_CREDENTIALS`: JSON payload for invalid logins
- `VALID_CREDENTIALS`: JSON payload for a valid login, used by smoke tests where appropriate
- `BRUTE_FORCE_MAX_FAILURES`: configured failure threshold (default: `5`)
- `BRUTE_FORCE_BLOCK_STATUS`: expected blocked response code (default: `429`)
- `RATE_LIMIT_REQUESTS_PER_MINUTE`: expected gateway limit (default: `100`)

> For `gateway-smoke-it.js`, set `VALID_CREDENTIALS` to a real login payload if the gateway is running against an authentication backend.

## Notes

The scripts are intentionally modular to let you run targeted scenarios and to keep all gateway k6 assets in one place.
