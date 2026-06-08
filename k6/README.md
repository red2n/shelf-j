# k6 Test Suite

This folder contains k6 test cases for realtime gateway validation, including load, stress, integration, and brute-force scenarios.

## Contents

- `common.js`: shared helper utilities and configuration values.
- `gateway-login-protection.js`: combined gateway login protection test for rate limiting and brute-force blocking.
- `gateway-rate-limit-stress.js`: high-concurrency stress test to verify gateway rate limiting.
- `gateway-smoke-it.js`: lightweight integration test for gateway login behavior.

## Run tests

Install `k6` first, then run one of the scripts:

```bash
k6 run k6/gateway-smoke-it.js
k6 run k6/gateway-login-protection.js
k6 run k6/gateway-rate-limit-stress.js
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
