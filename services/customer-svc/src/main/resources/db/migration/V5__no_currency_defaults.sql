-- SJ-D54: the tenant defaults SJ-D53 removed from the code were still written into the schema.
--
-- SJ-D53 took every currency and country literal out of the services, so a price list, a store credit
-- or a supplier is stamped with the tenant's own. The columns behind them kept a DEFAULT of 'GBP',
-- 'USD' or 'GB' from the first migrations: an insert that ever leaves the column out is filled in
-- with pounds or dollars and no error. Every insert binds the column today, so dropping the default
-- changes nothing that works and turns the next omission into a NOT NULL failure instead of a wrong
-- currency.

ALTER TABLE store_credit_accounts ALTER COLUMN currency DROP DEFAULT;
ALTER TABLE store_credit_ledger   ALTER COLUMN currency DROP DEFAULT;
