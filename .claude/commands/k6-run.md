# Run a k6 load test against the Docker stack

Usage: `/k6-run <script-name>` — e.g. `/k6-run order-crud`

The k6 tests always run against the live Docker stack (never against local/raw JVM processes).

```bash
k6 run k6/$ARGUMENTS.js
```

If `$ARGUMENTS` is empty, list available scripts:
```bash
ls k6/*.js
```

After a run, report:
- `http_req_duration` p50/p95/p99
- `http_req_failed` rate
- Any threshold breaches
- Any unexpected HTTP status codes (non-2xx outside of expected auth errors)
