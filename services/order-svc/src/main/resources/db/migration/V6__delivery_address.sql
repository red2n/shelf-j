-- Structured home-delivery address, captured when fulfilment_type = 'DELIVERY'. Nullable: pickup
-- and in-store orders never populate these. Enforced at the service layer (required-when-DELIVERY),
-- not a DB CHECK, since the column set differs per fulfilment type rather than being a fixed rule.
ALTER TABLE orders
    ADD COLUMN delivery_line1           TEXT,
    ADD COLUMN delivery_line2           TEXT,
    ADD COLUMN delivery_city            TEXT,
    ADD COLUMN delivery_postal_code     TEXT,
    ADD COLUMN delivery_recipient_name  TEXT,
    ADD COLUMN delivery_recipient_phone TEXT;
