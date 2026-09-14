-- SJ-D54: the tenant defaults SJ-D53 removed from the code were still written into the schema.
--
-- SJ-D53 took every currency and country literal out of the services, so a price list, a store credit
-- or a supplier is stamped with the tenant's own. The columns behind them kept a DEFAULT of 'GBP',
-- 'USD' or 'GB' from the first migrations: an insert that ever leaves the column out is filled in
-- with pounds or dollars and no error. Every insert binds the column today, so dropping the default
-- changes nothing that works and turns the next omission into a NOT NULL failure instead of a wrong
-- currency.

ALTER TABLE suppliers             ALTER COLUMN country_code DROP DEFAULT;
ALTER TABLE suppliers             ALTER COLUMN currency     DROP DEFAULT;
ALTER TABLE purchase_orders       ALTER COLUMN currency     DROP DEFAULT;
ALTER TABLE intercompany_invoices ALTER COLUMN currency     DROP DEFAULT;
