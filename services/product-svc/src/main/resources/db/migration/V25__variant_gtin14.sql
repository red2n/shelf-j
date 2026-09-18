-- The one form a scanned code is matched against (07.15).
--
-- The same trade item is written four ways. A shop types the EAN-13 off the shelf edge; the case in
-- the stockroom carries the GTIN-14 of the same item; North American stock carries a UPC-A; a small
-- pack carries a GTIN-8. A GS1 2D code — DataMatrix or a Digital Link QR — always carries the
-- 14-digit form. So a till that compares strings decides that the packet in the customer's hand is a
-- different product from the one on the shelf edge, and finds nothing.
--
-- GS1's own rule is that the shorter forms ARE the 14-digit form with leading zeros. This adds that
-- form as a GENERATED column rather than a column the service writes, for one reason: it cannot
-- drift. A barcode corrected in the admin screen, a bulk import, a migration that touches the row —
-- every one of them updates the match key, because the database computes it. A column maintained in
-- application code would be right until the first write path that forgot it, and the symptom would be
-- an item that scans at one till and not at another.
--
-- The check digit is verified here too, so the column holds a GTIN or nothing. A barcode that is not
-- a GTIN — an internal code, a shelf label, a PLU — keeps working through the existing exact-match
-- lookup; it simply has no GTIN form, which is the truth about it.

-- The GS1 modulo-10 check, as a function so the generated column below is readable rather than
-- fourteen repetitions of lpad(). IMMUTABLE because a generated column may only call functions whose
-- answer depends on nothing but their arguments — which is true here and worth stating.
--
-- After padding to fourteen, the weights are fixed positions: counting from the check digit
-- backwards they alternate 3 and 1, which puts weight 3 on every odd position of the padded string
-- and 1 on every even one. Anchoring the weights on the left instead is correct for one GTIN length
-- and wrong for the other three — it reads EAN-13 and refuses every case code.
CREATE FUNCTION gs1_check_digit_holds(code TEXT) RETURNS BOOLEAN AS $$
    SELECT (
        10 - (
            3 * (
                substr(padded, 1, 1)::INT + substr(padded, 3, 1)::INT + substr(padded, 5, 1)::INT
              + substr(padded, 7, 1)::INT + substr(padded, 9, 1)::INT + substr(padded, 11, 1)::INT
              + substr(padded, 13, 1)::INT
            )
            + (
                substr(padded, 2, 1)::INT + substr(padded, 4, 1)::INT + substr(padded, 6, 1)::INT
              + substr(padded, 8, 1)::INT + substr(padded, 10, 1)::INT + substr(padded, 12, 1)::INT
            )
        ) % 10
    ) % 10 = substr(padded, 14, 1)::INT
    FROM (SELECT lpad(code, 14, '0') AS padded) AS p;
$$ LANGUAGE SQL IMMUTABLE STRICT;

COMMENT ON FUNCTION gs1_check_digit_holds(TEXT) IS
    'Whether a numeric GTIN of 8, 12, 13 or 14 digits satisfies the GS1 modulo-10 check digit.';

-- The normalised GTIN, or NULL when the barcode is not a GTIN at all.
ALTER TABLE product_variants
    ADD COLUMN gtin14 TEXT GENERATED ALWAYS AS (
        CASE
            WHEN barcode IS NULL THEN NULL
            WHEN barcode !~ '^[0-9]+$' THEN NULL
            WHEN length(barcode) NOT IN (8, 12, 13, 14) THEN NULL
            WHEN NOT gs1_check_digit_holds(barcode) THEN NULL
            ELSE lpad(barcode, 14, '0')
        END
    ) STORED;

-- The scan path's index. Not UNIQUE: uq_variants_tenant_barcode already keeps one variant per
-- barcode, and two different barcodes cannot produce the same GTIN-14 — but an existing tenant could
-- hold both '5012345678900' and '05012345678900', entered at different times by different people,
-- and a unique index added now would fail the migration on their data rather than letting them fix
-- it. The lookup takes the first match by a stable order instead.
CREATE INDEX idx_variants_tenant_gtin14
    ON product_variants (tenant_id, gtin14)
    WHERE gtin14 IS NOT NULL;

COMMENT ON COLUMN product_variants.gtin14 IS
    'The barcode as a 14-digit GTIN, generated and check-digit verified; NULL when it is not a GTIN.';
