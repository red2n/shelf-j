# Check the current diff against Shelf-J golden rules

Review every file changed on the current branch against the 15 golden rules in CLAUDE.md. Focus on the non-negotiable ones that are easy to miss:

1. **No cross-service DB access** — grep for cross-schema joins or references to another service's tables.
2. **`tenant_id` from JWT only** — grep for `tenant_id` appearing in request body deserialization, `@PathParam`, or `@QueryParam`.
3. **Controllers thin** — check `api/` classes for any `EntityManager`, `DataSource`, or repository calls.
4. **DTOs in/out** — check that no JPA `@Entity` class is returned from a JAX-RS resource method.
5. **Money as BigDecimal/NUMERIC** — grep for `double` or `float` used for price/amount/vat fields.
6. **Append-only tables** — check that `stock_movements`, `order_status_history`, `payments`, `refunds`, `loyalty_ledger`, `audit_log` are never `UPDATE`-d or `DELETE`-d.
7. **Outbox pattern** — any new domain event must be written to `outbox` in the same DB transaction as the state change.
8. **Flutter: `showPrices` guard** — any new widget that renders a price must check `storefrontShowPricesProvider`.

Run:
```bash
git diff main...HEAD --name-only
git diff main...HEAD
```

Then read the relevant changed files and report:
- Which rules pass ✓
- Which rules have violations ✗ — quote the exact file:line and explain why it breaks the rule
- Which rules are not applicable to this diff (N/A)
