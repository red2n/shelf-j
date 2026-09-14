-- 10.8: the generational tobacco ban.
--
-- The Tobacco and Vapes Act 2026 (Royal Assent 29 April 2026) makes it an offence to sell tobacco to
-- anyone born on or after 1 January 2009, however old they are. That is a date of birth, not an
-- age, and a rule table that only holds a minimum age cannot write it: in 2040 a 31-year-old born
-- in 2009 is still refused. The minimum age stays — it is still the rule for everyone born before
-- the cut-off — and the cut-off sits beside it with the day it takes effect.
--
-- Anyone the cut-off catches is under 18 until 1 January 2027, so the minimum age already refuses
-- them until then; the effective date keeps the till's prompt honest rather than changing who
-- is served.

ALTER TABLE age_restriction_rules
    -- Refuse anyone born on or after this date, whatever their age.
    ADD COLUMN born_before      DATE,
    -- The day the cut-off takes effect; a statutory cut-off always has one.
    ADD COLUMN born_before_from DATE,
    ADD CONSTRAINT chk_age_born_before_pair CHECK ((born_before IS NULL) = (born_before_from IS NULL));

-- A business may adopt a cut-off early, or an earlier one, as its own policy. It applies at once.
ALTER TABLE tenant_age_restriction_rules
    ADD COLUMN born_before DATE;

UPDATE age_restriction_rules
   SET born_before      = DATE '2009-01-01',
       born_before_from = DATE '2027-01-01',
       note             = 'Children and Young Persons (Protection from Tobacco) Act 1991; Tobacco and Vapes Act 2026: no sale to anyone born on or after 1 Jan 2009'
 WHERE country = 'GB'
   AND category = 'TOBACCO';
