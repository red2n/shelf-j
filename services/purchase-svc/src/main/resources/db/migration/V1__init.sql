-- purchase-svc schema — Gap #20: Intercompany invoicing, FRS 102 nominal ledger
-- UK GAAP / HMRC compliant. All monetary values in NUMERIC. All timestamps UTC.

SET search_path TO purchase;

-- ── Suppliers ─────────────────────────────────────────────────────────────────
CREATE TABLE suppliers (
  id                  UUID        PRIMARY KEY,
  tenant_id           UUID        NOT NULL,
  name                VARCHAR(200) NOT NULL,
  vat_number          VARCHAR(20),
  vat_registered      BOOLEAN     NOT NULL DEFAULT false,
  country_code        CHAR(2)     NOT NULL DEFAULT 'GB',
  currency            CHAR(3)     NOT NULL DEFAULT 'GBP',
  payment_terms_days  INT         NOT NULL DEFAULT 30,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX suppliers_tenant     ON suppliers(tenant_id);
CREATE UNIQUE INDEX suppliers_tenant_name ON suppliers(tenant_id, name);

-- ── Purchase Orders ───────────────────────────────────────────────────────────
CREATE TABLE purchase_orders (
  id                UUID        PRIMARY KEY,
  tenant_id         UUID        NOT NULL,
  supplier_id       UUID        NOT NULL,
  store_id          UUID        NOT NULL,
  status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  currency          CHAR(3)     NOT NULL DEFAULT 'GBP',
  total_net         NUMERIC(14,2) NOT NULL DEFAULT 0,
  total_vat         NUMERIC(14,2) NOT NULL DEFAULT 0,
  total_gross       NUMERIC(14,2) NOT NULL DEFAULT 0,
  expected_delivery DATE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT po_status CHECK (status IN ('DRAFT','SUBMITTED','RECEIVED','CANCELLED'))
);
CREATE INDEX po_tenant          ON purchase_orders(tenant_id);
CREATE INDEX po_tenant_supplier ON purchase_orders(tenant_id, supplier_id);
CREATE INDEX po_tenant_store    ON purchase_orders(tenant_id, store_id);

-- ── Purchase Order Lines ──────────────────────────────────────────────────────
CREATE TABLE purchase_order_lines (
  id          UUID          PRIMARY KEY,
  tenant_id   UUID          NOT NULL,
  po_id       UUID          NOT NULL REFERENCES purchase_orders(id),
  variant_id  UUID          NOT NULL,
  qty         NUMERIC(14,3) NOT NULL,
  unit_price  NUMERIC(14,2) NOT NULL,
  vat_code    VARCHAR(10)   NOT NULL DEFAULT 'T1',
  created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX pol_tenant ON purchase_order_lines(tenant_id);
CREATE INDEX pol_po     ON purchase_order_lines(tenant_id, po_id);

-- ── Goods Receipts (GRN) ──────────────────────────────────────────────────────
CREATE TABLE goods_receipts (
  id           UUID        PRIMARY KEY,
  tenant_id    UUID        NOT NULL,
  po_id        UUID        NOT NULL,
  store_id     UUID        NOT NULL,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX gr_tenant ON goods_receipts(tenant_id);
CREATE INDEX gr_po     ON goods_receipts(tenant_id, po_id);

-- ── GRN Lines ─────────────────────────────────────────────────────────────────
CREATE TABLE goods_receipt_lines (
  id            UUID          PRIMARY KEY,
  tenant_id     UUID          NOT NULL,
  gr_id         UUID          NOT NULL REFERENCES goods_receipts(id),
  variant_id    UUID          NOT NULL,
  qty_received  NUMERIC(14,3) NOT NULL,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX grl_tenant ON goods_receipt_lines(tenant_id);
CREATE INDEX grl_gr     ON goods_receipt_lines(tenant_id, gr_id);

-- ── Intercompany Invoices (Gap #20: Oracle Inventory Ch. 19) ─────────────────
-- AR = Accounts Receivable raised by sending store
-- AP = Accounts Payable raised by receiving store
-- payment_due_date = invoice_date + payment_terms_days (BACS default 30)
-- vat_disregarded = true when both stores share a group VAT registration (HMRC VAT Notice 700/2)
CREATE TABLE intercompany_invoices (
  id               UUID          PRIMARY KEY,
  tenant_id        UUID          NOT NULL,
  invoice_type     VARCHAR(10)   NOT NULL,
  from_store_id    UUID          NOT NULL,
  to_store_id      UUID          NOT NULL,
  transfer_ref     UUID,
  net_amount       NUMERIC(14,2) NOT NULL,
  vat_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
  gross_amount     NUMERIC(14,2) NOT NULL,
  vat_code         VARCHAR(10)   NOT NULL DEFAULT 'T1',
  vat_disregarded  BOOLEAN       NOT NULL DEFAULT false,
  status           VARCHAR(20)   NOT NULL DEFAULT 'RAISED',
  invoice_date     DATE          NOT NULL DEFAULT CURRENT_DATE,
  payment_due_date DATE          NOT NULL,
  currency         CHAR(3)       NOT NULL DEFAULT 'GBP',
  created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT ii_type   CHECK (invoice_type IN ('AR','AP')),
  CONSTRAINT ii_status CHECK (status IN ('RAISED','SETTLED'))
);
CREATE INDEX ii_tenant      ON intercompany_invoices(tenant_id);
CREATE INDEX ii_tenant_type ON intercompany_invoices(tenant_id, invoice_type);
CREATE INDEX ii_tenant_from ON intercompany_invoices(tenant_id, from_store_id);
CREATE INDEX ii_transfer     ON intercompany_invoices(tenant_id, transfer_ref) WHERE transfer_ref IS NOT NULL;

-- ── Nominal Ledger Entries (FRS 102 / UK GAAP, double-entry, append-only) ────
-- Nominal codes follow Sage/Xero UK standard chart:
--   1100 Trade Debtors Control   1200 Bank Current Account
--   2100 Trade Creditors Control 2200 VAT Output  2201 VAT Input
--   4000 Sales - Intercompany    5000 Purchases - Intercompany
-- INVARIANT: debit = credit across entries for same source_ref (balanced journal)
-- NO UPDATE or DELETE paths — this table is append-only per FRS 102 s.2.51
CREATE TABLE nominal_ledger_entries (
  id            UUID          PRIMARY KEY,
  tenant_id     UUID          NOT NULL,
  entry_date    DATE          NOT NULL,
  nominal_code  VARCHAR(10)   NOT NULL,
  nominal_name  VARCHAR(100)  NOT NULL,
  debit         NUMERIC(14,2) NOT NULL DEFAULT 0,
  credit        NUMERIC(14,2) NOT NULL DEFAULT 0,
  description   VARCHAR(500)  NOT NULL,
  source_ref    UUID,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX nle_tenant      ON nominal_ledger_entries(tenant_id);
CREATE INDEX nle_tenant_code ON nominal_ledger_entries(tenant_id, nominal_code);
CREATE INDEX nle_tenant_date ON nominal_ledger_entries(tenant_id, entry_date);

-- ── Outbox ────────────────────────────────────────────────────────────────────
CREATE TABLE outbox (
  id            UUID        PRIMARY KEY,
  event_type    VARCHAR(100) NOT NULL,
  topic         VARCHAR(200) NOT NULL,
  tenant_id     UUID        NOT NULL,
  aggregate_id  UUID        NOT NULL,
  payload       TEXT        NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  published     BOOLEAN     NOT NULL DEFAULT false,
  published_at  TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox(published, created_at) WHERE NOT published;
