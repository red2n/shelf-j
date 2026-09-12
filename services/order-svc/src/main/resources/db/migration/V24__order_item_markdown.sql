-- 05.4 date-code markdown: a line sold at a reduced-price sticker records which markdown priced it,
-- so pricing-svc can count the sticker down and a report can say what reducing to clear cost.
ALTER TABLE order_items ADD COLUMN markdown_id UUID;

CREATE INDEX idx_order_items_markdown
    ON order_items (tenant_id, markdown_id)
    WHERE markdown_id IS NOT NULL;

-- A parked sale keeps its stickers too, so a resumed sale still rings at the sticker's price.
ALTER TABLE parked_sale_items ADD COLUMN markdown_id UUID;
