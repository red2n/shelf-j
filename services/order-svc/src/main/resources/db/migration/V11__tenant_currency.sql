-- SJ-D2: order-svc was stamping a hardcoded currency onto money-bearing rows.
--
-- Three call sites each picked their own literal ("USD" for orders and gift cards, "GBP" for
-- special orders) while pricing-svc resolved lines in the price list's currency, so a GBP tenant
-- could end up with GBP-priced lines on a USD-stamped order. tenants.currency has been captured at
-- onboarding since tenant-svc V1 and published on TenantCreated, but nothing ever read it.
--
-- Rather than add a synchronous call to tenant-svc on the checkout hot path, the currency is
-- projected onto the tenant_status row that this service already keeps (V5 flow-guard), fed by the
-- same TenantCreated event iam-svc already consumes. Nullable: tenants onboarded before this
-- migration have no row until their next TenantCreated, and resolveCurrency falls back to the
-- configured platform default (shelfj.order.currency.default) exactly as the status projection
-- fails open to ACTIVE.
ALTER TABLE tenant_status ADD COLUMN currency CHAR(3);

-- Column defaults disagreed across this schema: orders 'USD', gift_cards 'USD',
-- pos_log_entries 'USD', special_orders 'GBP'. All four INSERTs already name the currency column
-- explicitly (OrderRepository lines 47, 677, 1154, 1382), so the defaults are unreachable today --
-- they exist only to silently stamp the wrong currency onto money if a future INSERT forgets the
-- column. Drop them so that case fails loudly on a NOT NULL violation instead.
ALTER TABLE orders          ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE gift_cards      ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE special_orders  ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE pos_log_entries ALTER COLUMN currency DROP DEFAULT;
