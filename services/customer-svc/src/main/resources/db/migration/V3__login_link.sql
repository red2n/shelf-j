-- SJ-D44: a shopper's login and the shop's customer record were two ids nothing joined.
--
-- A login is global — one account shops across every storefront on the platform — while a customer
-- record belongs to one tenant. An online order recorded the login id in the same column a POS sale
-- uses for a customer id, so on live data not one order's customer_id belonged to a customer row.
-- Everything keyed on the customer therefore missed every online order: loyalty awarded nothing,
-- the confirmation email had no address to find, and erasure (UK GDPR art.17) could not reach the
-- delivery name, phone and address the order holds.
--
-- The join is recorded here, on the side that is tenant-scoped: at most one customer per login per
-- tenant. NULL stays ordinary — a POS walk-in created at the till has no login and never will.
ALTER TABLE customers ADD COLUMN login_id UUID;

CREATE UNIQUE INDEX uq_customers_login
    ON customers (tenant_id, login_id) WHERE login_id IS NOT NULL;

-- A person who signs in and buys has given a shop their email, not their name. The columns were
-- NOT NULL because every customer used to be typed in by staff, who had a name in front of them;
-- a linked login often has none until the shopper fills one in, and an empty string pretending to
-- be a name is worse than an absent one.
ALTER TABLE customers ALTER COLUMN first_name DROP NOT NULL;
ALTER TABLE customers ALTER COLUMN last_name  DROP NOT NULL;
