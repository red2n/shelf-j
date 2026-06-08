# Gateway `k6` login protection test

This directory contains a `k6` script for exercising the gateway's login rate limiting and brute-force protection.

## What it tests

- A burst of login requests to verify the gateway can return `429` when rate limiting is triggered.
- A sequence of invalid login attempts to verify brute-force blocking after the configured failure threshold.

## Run locally

Install `k6` first:

```bash
# macOS
brew install k6

# Linux
sudo apt install k6
```

Then run:

```bash
BASE_URL=http://localhost:8080 \
LOGIN_PATH=/auth \
k6 run platform/gateway/k6/login-protection.js
```

## Optional environment variables

- `BASE_URL`: the gateway base URL (default: `http://localhost:8080`)
- `LOGIN_PATH`: the login endpoint path (default: `/auth`)
- `INVALID_CREDENTIALS`: JSON payload used for invalid login attempts
- `BRUTE_FORCE_MAX_FAILURES`: the configured failure threshold, used to validate blocking behavior
- `BRUTE_FORCE_BLOCK_STATUS`: the expected HTTP status when a client is blocked (default: `429`)

## Example

```bash
BASE_URL=http://localhost:8080 \
LOGIN_PATH=/auth \
INVALID_CREDENTIALS='{"username":"bad","password":"bad"}' \
k6 run platform/gateway/k6/login-protection.js
```
