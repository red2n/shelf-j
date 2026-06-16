-- Per-store storefront pricing display. When false, the online storefront for this
-- store hides product prices and shows stock availability instead; ordering still works.
ALTER TABLE stores ADD COLUMN show_prices BOOLEAN NOT NULL DEFAULT true;
