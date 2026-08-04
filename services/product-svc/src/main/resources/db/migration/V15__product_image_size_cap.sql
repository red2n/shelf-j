-- Enforce the product-image size invariant at the storage layer: every stored image is
-- strictly under 256 KB.
--
-- ProductService.MAX_IMAGE_BYTES already rejects oversized uploads at the API boundary,
-- and the admin app compresses to the same budget before it uploads. This constraint is
-- what makes it an invariant rather than a convention: images are read back in full on
-- every storefront render, so nothing may depend on a client — or a future service — having
-- done the right thing on the way in.
--
-- NOT VALID: enforced on every INSERT and UPDATE from here on, but existing rows are not
-- re-checked, so this cannot fail on a database that accepted the previous 512 KB cap.
-- Any legacy row above the cap keeps serving until it is next replaced.
--
-- To find legacy rows still in breach:
--   SELECT tenant_id, product_id, octet_length(bytes) AS size_bytes
--     FROM product_images
--    WHERE octet_length(bytes) >= 262144
--    ORDER BY size_bytes DESC;
--
-- Once that returns nothing, promote the constraint to fully enforced with:
--   ALTER TABLE product_images VALIDATE CONSTRAINT product_images_size_under_256kb;
ALTER TABLE product_images
    ADD CONSTRAINT product_images_size_under_256kb
    CHECK (octet_length(bytes) < 262144) NOT VALID;
