-- Promote the 256 KB product-image cap from "enforced going forward" to a true invariant.
--
-- V15 added product_images_size_under_256kb as NOT VALID, deliberately: it had to be safe to
-- apply to a database that had been accepting the previous 512 KB cap, so existing rows were
-- never re-checked. That left the invariant half-true — every new write is bounded, but nothing
-- guarantees what is already stored, and images are read back in full on every storefront
-- render. "No image over 256 KB in the system" was a statement about the future only.
--
-- This runs as a migration rather than as a one-off psql command so that every environment ends
-- up in the same state. A constraint that is validated on one database and NOT VALID on another
-- is worse than either, because no one can say which invariants actually hold.
--
-- The pre-check exists because VALIDATE CONSTRAINT's own error names the constraint but not the
-- data, which leaves an operator with a failed deploy and no next step. Migrations run as a
-- run-once Job before any business service starts, so failing here is a blocked deploy, not an
-- outage — the right place to find out that stored data breaks an invariant.
DO $$
DECLARE
    breaching bigint;
    largest   bigint;
BEGIN
    SELECT count(*), COALESCE(max(octet_length(bytes)), 0)
      INTO breaching, largest
      FROM product_images
     WHERE octet_length(bytes) >= 262144;

    IF breaching > 0 THEN
        RAISE EXCEPTION
            'Cannot validate the 256 KB product image cap: % row(s) are at or above it (largest % bytes).',
            breaching, largest
        USING HINT =
            'List them with: SELECT tenant_id, product_id, octet_length(bytes) AS size_bytes '
            'FROM product_images WHERE octet_length(bytes) >= 262144 ORDER BY size_bytes DESC; '
            'Re-upload or delete each one (the admin app compresses to the cap on upload), then '
            'run this migration again.';
    END IF;
END
$$;

ALTER TABLE product_images VALIDATE CONSTRAINT product_images_size_under_256kb;
