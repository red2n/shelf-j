-- V17: Tier-1 gap completions (items 21-31)
-- 21 Transaction reason codes (controlled vocabulary)
CREATE TABLE transaction_reason_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    code        TEXT        NOT NULL,
    description TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_reason_codes_tenant ON transaction_reason_codes (tenant_id);

-- Seed system-wide reason codes (tenant_id = all-zeros sentinel)
INSERT INTO transaction_reason_codes (tenant_id, code, description) VALUES
  ('00000000-0000-0000-0000-000000000000', 'DAMAGED',       'Stock damaged'),
  ('00000000-0000-0000-0000-000000000000', 'FOUND',         'Stock found during count'),
  ('00000000-0000-0000-0000-000000000000', 'THEFT',         'Shrinkage / theft'),
  ('00000000-0000-0000-0000-000000000000', 'EXPIRY',        'Expired and written off'),
  ('00000000-0000-0000-0000-000000000000', 'VENDOR_RETURN', 'Returned to vendor'),
  ('00000000-0000-0000-0000-000000000000', 'CORRECTION',    'Data-entry correction'),
  ('00000000-0000-0000-0000-000000000000', 'SAMPLING',      'Quality sampling');

-- Add reason_code column to stock_movements (nullable — historic rows have none)
ALTER TABLE stock_movements ADD COLUMN reason_code TEXT;

-- 22 Configurable transaction source types
CREATE TABLE transaction_source_types (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    code        TEXT        NOT NULL,
    description TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_source_types_tenant ON transaction_source_types (tenant_id);

INSERT INTO transaction_source_types (tenant_id, code, description) VALUES
  ('00000000-0000-0000-0000-000000000000', 'RECEIVE',    'Goods receipt'),
  ('00000000-0000-0000-0000-000000000000', 'SALE',       'POS or online sale'),
  ('00000000-0000-0000-0000-000000000000', 'ADJUST',     'Manual adjustment'),
  ('00000000-0000-0000-0000-000000000000', 'TRANSFER',   'Inter/intra store transfer'),
  ('00000000-0000-0000-0000-000000000000', 'RETURN',     'Customer return'),
  ('00000000-0000-0000-0000-000000000000', 'RESERVE',    'Reservation hold'),
  ('00000000-0000-0000-0000-000000000000', 'RELEASE',    'Reservation release'),
  ('00000000-0000-0000-0000-000000000000', 'CYCLE_COUNT','Cycle count adjustment'),
  ('00000000-0000-0000-0000-000000000000', 'LOT_SPLIT',  'Lot split action'),
  ('00000000-0000-0000-0000-000000000000', 'LOT_MERGE',  'Lot merge action');

-- 23 Lot action codes — lot_actions table (split/merge create new batches)
CREATE TABLE lot_actions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    action_type     TEXT        NOT NULL,  -- SPLIT | MERGE
    source_batch_id UUID        NOT NULL,
    result_batch_id UUID        NOT NULL,
    qty             NUMERIC(18,3) NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lot_actions_tenant   ON lot_actions (tenant_id, created_at DESC);
CREATE INDEX idx_lot_actions_source   ON lot_actions (tenant_id, source_batch_id);
CREATE INDEX idx_lot_actions_result   ON lot_actions (tenant_id, result_batch_id);

-- 25 Grade control — grade column on inventory_batches
ALTER TABLE inventory_batches ADD COLUMN grade TEXT;   -- e.g. A | B | C | REJECT

-- 26 Lot-specific UOM conversions
CREATE TABLE lot_uom_conversions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID           NOT NULL,
    batch_id    UUID           NOT NULL,
    from_uom    TEXT           NOT NULL,
    to_uom      TEXT           NOT NULL,
    factor      NUMERIC(18,6)  NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, batch_id, from_uom, to_uom)
);
CREATE INDEX idx_lot_uom_tenant ON lot_uom_conversions (tenant_id, batch_id);

-- 27 PAR levels / replenishment counting
CREATE TABLE par_level_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    variant_id      UUID          NOT NULL,
    par_qty         NUMERIC(18,3) NOT NULL,
    uom             TEXT,
    review_cycle    TEXT          NOT NULL DEFAULT 'DAILY',  -- DAILY | WEEKLY | MONTHLY
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, store_id, variant_id)
);
CREATE INDEX idx_par_level_tenant ON par_level_configs (tenant_id, store_id);

-- 28 Order modifiers on reorder_point_plans and kanban_cards
ALTER TABLE reorder_point_plans ADD COLUMN min_order_qty   NUMERIC(18,3);
ALTER TABLE reorder_point_plans ADD COLUMN max_order_qty   NUMERIC(18,3);
ALTER TABLE reorder_point_plans ADD COLUMN lot_multiplier  NUMERIC(18,3);
ALTER TABLE kanban_cards        ADD COLUMN min_order_qty   NUMERIC(18,3);
ALTER TABLE kanban_cards        ADD COLUMN max_order_qty   NUMERIC(18,3);
ALTER TABLE kanban_cards        ADD COLUMN lot_multiplier  NUMERIC(18,3);

-- 31 GL account mapping — zone/subinventory → nominal code
CREATE TABLE zone_gl_mappings (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    store_id     UUID NOT NULL,
    zone_id      UUID,                    -- NULL = store-level default
    nominal_code TEXT NOT NULL,
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, store_id, zone_id)
);
CREATE INDEX idx_zone_gl_tenant ON zone_gl_mappings (tenant_id, store_id);
