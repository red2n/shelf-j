-- Gap #36: 18 Oracle item attribute groups.
-- System-seeded reference tables (no tenant_id) define the groups and their typed fields.
-- Tenant-scoped variant_attribute_group_values stores per-variant values as JSONB.

CREATE TABLE item_attribute_groups (
    group_code  TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE item_attribute_group_fields (
    id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    group_code  TEXT    NOT NULL REFERENCES item_attribute_groups(group_code) ON DELETE CASCADE,
    field_code  TEXT    NOT NULL,
    label       TEXT    NOT NULL,
    data_type   TEXT    NOT NULL CHECK (data_type IN ('TEXT','NUMBER','BOOLEAN')),
    required    BOOLEAN NOT NULL DEFAULT false,
    sort_order  INT     NOT NULL DEFAULT 0,
    UNIQUE (group_code, field_code)
);
CREATE INDEX idx_iagf_group ON item_attribute_group_fields (group_code);

CREATE TABLE variant_attribute_group_values (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    variant_id  UUID        NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    group_code  TEXT        NOT NULL REFERENCES item_attribute_groups(group_code),
    values      JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id, group_code)
);
CREATE INDEX idx_vagv_tenant_variant ON variant_attribute_group_values (tenant_id, variant_id);

-- ── Seed the 18 Oracle attribute groups ──────────────────────────────────────

INSERT INTO item_attribute_groups (group_code, name, description) VALUES
  ('MAIN',               'Main',                   'Core item control flags: lot, serial, revision, primary UOM'),
  ('COSTING',            'Costing',                'Cost method enablement and rollup settings'),
  ('PURCHASING',         'Purchasing',             'Buyer-facing pricing, UOM, and delivery rules'),
  ('RECEIVING',          'Receiving',              'Receipt routing, tolerances, and inspection rules'),
  ('PHYSICAL_ATTRIBUTES','Physical Attributes',    'Weight, volume, and container dimensions'),
  ('GENERAL_PLANNING',   'General Planning',       'Reorder method, safety stock, and min/max quantities'),
  ('MRP_MPS_PLANNING',   'MRP / MPS Planning',     'MRP planning method, make/buy code, ATP'),
  ('LEAD_TIMES',         'Lead Times',             'Preprocessing, processing, and post-processing days'),
  ('WIP',                'Work In Process',        'WIP supply type and overcompletion tolerance'),
  ('ORDER_MANAGEMENT',   'Order Management',       'Customer ordering, shippability, and return rules'),
  ('INVOICING',          'Invoicing',              'Invoice enablement, accounting and tax rules'),
  ('SERVICE',            'Service',                'Serviceability, warranty, and contract item settings'),
  ('WEB',                'Web',                    'E-commerce visibility and web description'),
  ('ASSET_MGMT',         'Asset Management',       'Capital asset tracking and depreciation category'),
  ('BOM',                'Bill of Materials',      'BOM item type and engineering item flag'),
  ('QUALITY',            'Quality',                'Inspection enablement and skip-lot percentage'),
  ('HAZMAT',             'Hazardous Material',     'Dangerous goods classification and UN number'),
  ('PROCESS_MFG',        'Process Manufacturing',  'Process recipe, costing, and execution flags');

-- ── MAIN ─────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('MAIN','primary_uom',             'Primary UOM',                    'TEXT',    false, 1),
  ('MAIN','lot_control_code',        'Lot Control',                    'TEXT',    false, 2),
  ('MAIN','serial_number_control',   'Serial Number Control',          'TEXT',    false, 3),
  ('MAIN','revision_qty_control',    'Revision Qty Control',           'TEXT',    false, 4),
  ('MAIN','restrict_subinventories', 'Restrict Subinventories',        'BOOLEAN', false, 5),
  ('MAIN','restrict_locators',       'Restrict Locators',              'BOOLEAN', false, 6);

-- ── COSTING ──────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('COSTING','costing_enabled',            'Costing Enabled',               'BOOLEAN', false, 1),
  ('COSTING','inventory_asset_value',      'Inventory Asset Value',         'BOOLEAN', false, 2),
  ('COSTING','default_include_in_rollup',  'Include in Cost Rollup',        'BOOLEAN', false, 3),
  ('COSTING','std_lot_size',               'Standard Lot Size',             'NUMBER',  false, 4);

-- ── PURCHASING ───────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('PURCHASING','list_price',                'List Price',                    'NUMBER',  false, 1),
  ('PURCHASING','market_price',              'Market Price',                  'NUMBER',  false, 2),
  ('PURCHASING','price_tolerance_pct',       'Price Tolerance %',             'NUMBER',  false, 3),
  ('PURCHASING','allow_express_delivery',    'Allow Express Delivery',        'BOOLEAN', false, 4),
  ('PURCHASING','allow_unordered_receipts',  'Allow Unordered Receipts',      'BOOLEAN', false, 5),
  ('PURCHASING','allow_substitute_receipts', 'Allow Substitute Receipts',     'BOOLEAN', false, 6);

-- ── RECEIVING ────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('RECEIVING','receipt_routing',            'Receipt Routing',               'TEXT',    false, 1),
  ('RECEIVING','qty_rcv_tolerance_pct',      'Qty Receive Tolerance %',       'NUMBER',  false, 2),
  ('RECEIVING','days_early_receipt_allowed', 'Days Early Receipt Allowed',    'NUMBER',  false, 3),
  ('RECEIVING','days_late_receipt_allowed',  'Days Late Receipt Allowed',     'NUMBER',  false, 4),
  ('RECEIVING','over_receipt_action',        'Over Receipt Action',           'TEXT',    false, 5),
  ('RECEIVING','enforce_ship_to_loc',        'Enforce Ship-To Location',      'BOOLEAN', false, 6);

-- ── PHYSICAL_ATTRIBUTES ──────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('PHYSICAL_ATTRIBUTES','unit_weight',      'Unit Weight',                   'NUMBER',  false, 1),
  ('PHYSICAL_ATTRIBUTES','weight_uom',       'Weight UOM',                    'TEXT',    false, 2),
  ('PHYSICAL_ATTRIBUTES','unit_volume',      'Unit Volume',                   'NUMBER',  false, 3),
  ('PHYSICAL_ATTRIBUTES','volume_uom',       'Volume UOM',                    'TEXT',    false, 4),
  ('PHYSICAL_ATTRIBUTES','container_type_code','Container Type',              'TEXT',    false, 5);

-- ── GENERAL_PLANNING ─────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('GENERAL_PLANNING','planning_method',     'Planning Method',               'TEXT',    false, 1),
  ('GENERAL_PLANNING','safety_stock_method', 'Safety Stock Method',           'TEXT',    false, 2),
  ('GENERAL_PLANNING','safety_stock_pct',    'Safety Stock %',                'NUMBER',  false, 3),
  ('GENERAL_PLANNING','safety_stock_days',   'Safety Stock Days',             'NUMBER',  false, 4),
  ('GENERAL_PLANNING','min_minmax_qty',      'Min Min-Max Qty',               'NUMBER',  false, 5),
  ('GENERAL_PLANNING','max_minmax_qty',      'Max Min-Max Qty',               'NUMBER',  false, 6),
  ('GENERAL_PLANNING','fixed_order_qty',     'Fixed Order Qty',               'NUMBER',  false, 7),
  ('GENERAL_PLANNING','fixed_days_supply',   'Fixed Days Supply',             'NUMBER',  false, 8);

-- ── MRP_MPS_PLANNING ─────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('MRP_MPS_PLANNING','mrp_planning_method', 'MRP Planning Method',           'TEXT',    false, 1),
  ('MRP_MPS_PLANNING','make_buy_code',       'Make/Buy Code',                 'TEXT',    false, 2),
  ('MRP_MPS_PLANNING','calculate_atp',       'Calculate ATP',                 'BOOLEAN', false, 3),
  ('MRP_MPS_PLANNING','capable_to_promise',  'Capable to Promise',            'BOOLEAN', false, 4);

-- ── LEAD_TIMES ───────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('LEAD_TIMES','preprocessing_days',        'Pre-Processing Days',           'NUMBER',  false, 1),
  ('LEAD_TIMES','processing_days',           'Processing Days',               'NUMBER',  false, 2),
  ('LEAD_TIMES','post_processing_days',      'Post-Processing Days',          'NUMBER',  false, 3);

-- ── WIP ──────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('WIP','supply_type',                      'Supply Type',                   'TEXT',    false, 1),
  ('WIP','wip_enabled',                      'WIP Enabled',                   'BOOLEAN', false, 2),
  ('WIP','overcompletion_tolerance_pct',     'Overcompletion Tolerance %',    'NUMBER',  false, 3);

-- ── ORDER_MANAGEMENT ─────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('ORDER_MANAGEMENT','customer_ordered',    'Customer Ordered',              'BOOLEAN', false, 1),
  ('ORDER_MANAGEMENT','shippable_item',      'Shippable Item',                'BOOLEAN', false, 2),
  ('ORDER_MANAGEMENT','returnable',          'Returnable',                    'BOOLEAN', false, 3),
  ('ORDER_MANAGEMENT','oe_transactable',     'OE Transactable',               'BOOLEAN', false, 4);

-- ── INVOICING ────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('INVOICING','invoiceable_item',           'Invoiceable Item',              'BOOLEAN', false, 1),
  ('INVOICING','invoice_enabled',            'Invoice Enabled',               'BOOLEAN', false, 2),
  ('INVOICING','accounting_rule_id',         'Accounting Rule',               'TEXT',    false, 3),
  ('INVOICING','tax_code',                   'Tax Code',                      'TEXT',    false, 4);

-- ── SERVICE ──────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('SERVICE','serviceable_product',          'Serviceable Product',           'BOOLEAN', false, 1),
  ('SERVICE','service_starting_delay_days',  'Service Starting Delay (days)', 'NUMBER',  false, 2),
  ('SERVICE','contract_item_type',           'Contract Item Type',            'TEXT',    false, 3),
  ('SERVICE','warranty_vendor_id',           'Warranty Vendor',               'TEXT',    false, 4);

-- ── WEB ──────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('WEB','web_status',                       'Web Status',                    'TEXT',    false, 1),
  ('WEB','browsable',                        'Browsable',                     'BOOLEAN', false, 2),
  ('WEB','web_long_description',             'Web Long Description',          'TEXT',    false, 3);

-- ── ASSET_MGMT ───────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('ASSET_MGMT','asset_creation',            'Asset Creation',                'TEXT',    false, 1),
  ('ASSET_MGMT','asset_category_id',         'Asset Category',                'TEXT',    false, 2),
  ('ASSET_MGMT','capitalize',                'Capitalize',                    'BOOLEAN', false, 3);

-- ── BOM ──────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('BOM','bom_item_type',                    'BOM Item Type',                 'TEXT',    false, 1),
  ('BOM','bom_enabled',                      'BOM Enabled',                   'BOOLEAN', false, 2),
  ('BOM','eng_item',                         'Engineering Item',               'BOOLEAN', false, 3);

-- ── QUALITY ──────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('QUALITY','quality_inspection_enabled',   'Quality Inspection Enabled',    'BOOLEAN', false, 1),
  ('QUALITY','skip_lot_pct',                 'Skip Lot %',                    'NUMBER',  false, 2);

-- ── HAZMAT ───────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('HAZMAT','hazardous_material',            'Hazardous Material',            'BOOLEAN', false, 1),
  ('HAZMAT','un_number',                     'UN Number',                     'TEXT',    false, 2),
  ('HAZMAT','hazard_class_id',               'Hazard Class',                  'TEXT',    false, 3),
  ('HAZMAT','shipping_name',                 'Proper Shipping Name',          'TEXT',    false, 4);

-- ── PROCESS_MFG ──────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (group_code,field_code,label,data_type,required,sort_order) VALUES
  ('PROCESS_MFG','process_item',             'Process Item',                  'BOOLEAN', false, 1),
  ('PROCESS_MFG','recipe_enabled',           'Recipe Enabled',                'BOOLEAN', false, 2),
  ('PROCESS_MFG','process_costing_enabled',  'Process Costing Enabled',       'BOOLEAN', false, 3),
  ('PROCESS_MFG','process_supply_type',      'Process Supply Type',           'TEXT',    false, 4);
