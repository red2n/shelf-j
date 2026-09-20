-- A departed business's data is erased when its retrieval period ends (21.14, EU Data Act
-- art.25(2)(h)). The prior-price ledger and the list-price history refuse every update and delete, so
-- that neither can be rewritten (V11); erasure is the one delete they allow, and only of the business
-- being erased, in the transaction that named it in storeql.erasing_tenant.
CREATE OR REPLACE FUNCTION applied_prices_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.tenant_id::text = current_setting('storeql.erasing_tenant', true) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$$;
