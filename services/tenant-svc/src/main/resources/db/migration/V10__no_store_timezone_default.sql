-- SJ-D54, the fourteenth literal: a store's time zone defaulted to 'UTC', in this column and in three
-- places in TenantService that filled a missing zone with it. A London store created without a zone
-- kept UTC's clock, an hour wrong for half the year, and updating any store without naming its zone
-- quietly reset it to UTC. No country has one right answer — the United States, Australia and
-- Brazil each span several zones — so the zone is now required when a store is created, kept when an
-- update leaves it out, and never defaulted.

ALTER TABLE stores ALTER COLUMN timezone DROP DEFAULT;
