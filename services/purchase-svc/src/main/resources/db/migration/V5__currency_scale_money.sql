-- SJ-D25: every money column in this service hard-coded two decimal places.
--
-- NUMERIC(14,2) is not a statement about money, it is a statement about sterling and the dollar.
-- Shelf-J is multi-currency, and ISO 4217 does not agree with that column type:
--
--   JPY, KRW, VND          0 minor units   ¥3,702 came back as 3702.00 — a precision the yen does
--                                          not have, on a figure that must be invoiced in whole yen
--   GBP, USD, EUR, INR,    2 minor units   correct by luck, because the schema was written for them
--   CNY
--   KWD, BHD, OMR, JOD,    3 minor units   1.234 KWD SILENTLY STORED AS 1.23 — real money lost on
--   TND                                    every line and every total, with no error anywhere
--
-- The third case is the one that makes this a defect rather than a cosmetic issue. Postgres rounds
-- to the column's declared scale on write without complaint, so a Kuwaiti tenant's purchase orders
-- were quietly wrong and nothing in the system could have noticed.
--
-- Unconstrained NUMERIC stores the value at the scale it is given, so the currency decides the
-- precision rather than the schema: Totals.of rounds at the currency's own minor units before the
-- write, and that scale now survives the round trip. Existing rows are untouched — they are already
-- scale 2, and every one of them is in a 2-minor-unit currency.
--
-- Quantity columns are deliberately left alone. NUMERIC(14,3) is a statement about how finely stock
-- is counted, which has nothing to do with what currency it is priced in.

-- Purchase order totals (SJ-D22 gave these their first writer; this gives them the right type).
ALTER TABLE purchase_orders
    ALTER COLUMN total_net   TYPE NUMERIC,
    ALTER COLUMN total_vat   TYPE NUMERIC,
    ALTER COLUMN total_gross TYPE NUMERIC;

-- A unit price is the one money column that legitimately carries MORE precision than the currency's
-- minor unit: 1,000 screws at £0.0125 each is an ordinary trade price, and NUMERIC(14,2) was
-- rounding it to £0.01 — a 25% error on the line, before any currency question arises.
ALTER TABLE purchase_order_lines
    ALTER COLUMN unit_price TYPE NUMERIC;

ALTER TABLE intercompany_invoices
    ALTER COLUMN net_amount   TYPE NUMERIC,
    ALTER COLUMN vat_amount   TYPE NUMERIC,
    ALTER COLUMN gross_amount TYPE NUMERIC;

ALTER TABLE nominal_ledger_entries
    ALTER COLUMN debit  TYPE NUMERIC,
    ALTER COLUMN credit TYPE NUMERIC;
