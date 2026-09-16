-- Item lifecycle states (readiness review: master data).
--
-- A line is listed before it goes on sale, sells, is run down and is taken off. ACTIVE and
-- DELISTED already existed; NEW_LINE (listed, not yet on sale — a launch day may be named) and
-- DISCONTINUED (still sold while stock lasts, never reordered) complete the four, so a line being
-- run down can be told from one on sale, and the till, the shop and replenishment each act on it.
ALTER TABLE products
    ADD COLUMN launch_on       DATE,         -- for a NEW_LINE: the day it is meant to go on sale
    ADD COLUMN discontinued_at TIMESTAMPTZ,  -- when the line was marked for run-down
    ADD CONSTRAINT chk_products_status
        CHECK (status IN ('NEW_LINE', 'ACTIVE', 'DISCONTINUED', 'DELISTED'));
