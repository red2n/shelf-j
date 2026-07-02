-- Contact phone for the customer/walk-in on PICKUP and INSTORE orders.
-- Delivery orders use delivery_recipient_phone instead.
ALTER TABLE orders ADD COLUMN contact_phone TEXT;
