-- France's e-reporting, on the statutory calendar (closing the code half of 18.9).
--
-- The e-invoicing reform has two limbs. One is the invoice itself, which 18.9 issues and sends. The
-- other is e-reporting: the transactions an invoice does NOT cover — a sale to a shopper, a sale
-- abroad — reported to the administration through the same platform, plus the payment data for
-- services. A business that issues every invoice correctly and reports nothing is still in breach,
-- and the reporting limb binds from the same day (1 Sep 2026 for large businesses and ETI,
-- 1 Sep 2027 for everyone else).
--
-- It belongs here because this is where a filing calendar already lives (07.14): the periods are
-- DERIVED on read from a frequency and an offset, and nothing is stored but what a business actually
-- filed. Two rows, because the law asks for two streams with the same cadence but different content.

-- A ten-day period is a real frequency in French tax law and not a rounding of "monthly": a business
-- on the ordinary monthly VAT regime reports three times a month. It cannot be expressed as a day
-- count — the third period of a month runs from the 21st to the 1st, which is 11 days in March and 8
-- in February — so it is a frequency the calendar derives, exactly like a quarter.
ALTER TABLE statutory_returns
    DROP CONSTRAINT ck_statutory_frequency;

ALTER TABLE statutory_returns
    ADD CONSTRAINT ck_statutory_frequency
    CHECK (frequency IN ('DECADAL', 'MONTHLY', 'QUARTERLY', 'ANNUAL'));

-- Transaction data: the B2C sales and the cross-border sales of the period, with the payload built
-- by order-svc, which owns the sales and already holds each line's VAT rate.
INSERT INTO statutory_returns
    (code, scope_kind, scope, name, frequency, due_after, export_service, export_path, citation,
     effective_from)
VALUES
    ('EREPORTING_TX_FR', 'COUNTRY', 'FR',
     'E-reporting: transaction data (données de transaction)', 'DECADAL', 'P10D',
     'order-svc', '/admin/ereporting/submissions',
     'CGI art. 290; décret n° 2022-1299 du 7 oct. 2022; LF 2024 art. 91 (dates)', '2026-09-01'),

-- Payment data: when the money for a service was actually received. Reported on the same cadence and
-- to the same platform, and kept as its own return because a business may owe one stream and not the
-- other — a shop selling only goods owes no payment data at all, and a calendar that hid that
-- distinction would show it a duty it does not have.
    ('EREPORTING_PAY_FR', 'COUNTRY', 'FR',
     'E-reporting: payment data (données de paiement, services)', 'DECADAL', 'P10D',
     'order-svc', '/admin/ereporting/submissions',
     'CGI art. 290 A; décret n° 2022-1299 du 7 oct. 2022', '2026-09-01');

COMMENT ON COLUMN statutory_returns.frequency IS
    'DECADAL (three ten-day periods a month, French e-reporting), MONTHLY, QUARTERLY or ANNUAL. Derived, never stored.';

-- The duty itself, named on the obligations sheet rather than left inside another row's summary. It
-- is a separate obligation in law: a business that issues every invoice correctly and reports nothing
-- is still in breach, and a sheet that folded the two together could not show that.
INSERT INTO legal_obligations (code, scope_kind, scope, effective_from, effective_to, citation, summary)
VALUES
 ('E_REPORTING','COUNTRY','FR',DATE '2026-09-01',NULL,
  'CGI art. 290 and 290 A; decret n. 2022-1299 du 7 oct. 2022',
  'Transactions no e-invoice covers — sales to consumers and abroad — and the payment data for services are transmitted to the administration through the business''s platform.');
