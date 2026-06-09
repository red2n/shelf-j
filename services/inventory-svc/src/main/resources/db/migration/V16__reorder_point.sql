-- Gap #19: Reorder Point planning with EOQ
-- ROP = average daily demand × lead time days + safety stock
-- EOQ = sqrt(2 × annual demand × ordering cost / holding cost per unit per year)

CREATE TABLE reorder_point_plans (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID            NOT NULL,
    store_id        UUID            NOT NULL,
    variant_id      UUID            NOT NULL,
    lead_time_days  INT             NOT NULL DEFAULT 7,
    ordering_cost   NUMERIC(18,2)   NOT NULL DEFAULT 0,    -- cost per order placed ($)
    holding_cost_pct NUMERIC(7,4)   NOT NULL DEFAULT 0.20, -- annual holding cost as % of unit cost
    unit_cost       NUMERIC(18,6)   NOT NULL DEFAULT 0,
    -- computed results (null until first compute run)
    avg_daily_demand NUMERIC(18,6),
    rop             NUMERIC(18,3),                         -- reorder point quantity
    eoq             NUMERIC(18,3),                         -- economic order quantity
    computed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT pk_rop_plans PRIMARY KEY (id),
    CONSTRAINT uq_rop_plan  UNIQUE (tenant_id, store_id, variant_id)
);

CREATE INDEX idx_rop_tenant ON reorder_point_plans (tenant_id, store_id);
