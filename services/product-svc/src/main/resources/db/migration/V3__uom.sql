-- Gap #2: Unit of Measure model (Oracle Inventory Ch. 3)
-- uom_classes and uom_definitions are system-wide reference data (no tenant_id).
-- uom_item_conversions are tenant + variant scoped.

CREATE TABLE uom_classes (
    id   UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT pk_uom_classes  PRIMARY KEY (id),
    CONSTRAINT uq_uom_class    UNIQUE (code)
);

CREATE TABLE uom_definitions (
    id         UUID NOT NULL,
    class_code TEXT NOT NULL,
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    CONSTRAINT pk_uom_definitions PRIMARY KEY (id),
    CONSTRAINT uq_uom_def         UNIQUE (class_code, code),
    CONSTRAINT uq_uom_code        UNIQUE (code)
);

-- Standard conversions: 1 from_uom = factor to_uom (system-wide, both directions seeded).
CREATE TABLE uom_standard_conversions (
    id       UUID           NOT NULL,
    from_uom TEXT           NOT NULL,
    to_uom   TEXT           NOT NULL,
    factor   NUMERIC(18,8)  NOT NULL,
    CONSTRAINT pk_uom_std_conv PRIMARY KEY (id),
    CONSTRAINT uq_uom_std_conv UNIQUE (from_uom, to_uom)
);

-- Item-level conversion overrides: tenant + variant scoped.
CREATE TABLE uom_item_conversions (
    id         UUID           NOT NULL,
    tenant_id  UUID           NOT NULL,
    variant_id UUID           NOT NULL,
    from_uom   TEXT           NOT NULL,
    to_uom     TEXT           NOT NULL,
    factor     NUMERIC(18,8)  NOT NULL,
    CONSTRAINT pk_uom_item_conv PRIMARY KEY (id),
    CONSTRAINT uq_uom_item_conv UNIQUE (tenant_id, variant_id, from_uom, to_uom)
);

CREATE INDEX idx_uom_item_tenant_variant
    ON uom_item_conversions (tenant_id, variant_id);

-- ── Seed: UOM classes ───────────────────────────────────────────────────────
INSERT INTO uom_classes (id, code, name) VALUES
  (gen_random_uuid(), 'EACH',   'Count / Each'),
  (gen_random_uuid(), 'WEIGHT', 'Weight'),
  (gen_random_uuid(), 'VOLUME', 'Volume'),
  (gen_random_uuid(), 'LENGTH', 'Length'),
  (gen_random_uuid(), 'TIME',   'Time'),
  (gen_random_uuid(), 'AREA',   'Area');

-- ── Seed: UOM definitions ───────────────────────────────────────────────────
INSERT INTO uom_definitions (id, class_code, code, name) VALUES
  -- EACH
  (gen_random_uuid(), 'EACH', 'EA',     'Each'),
  (gen_random_uuid(), 'EACH', 'PAIR',   'Pair'),
  (gen_random_uuid(), 'EACH', 'DOZEN',  'Dozen'),
  (gen_random_uuid(), 'EACH', 'SCORE',  'Score'),
  (gen_random_uuid(), 'EACH', 'GROSS',  'Gross (144)'),
  (gen_random_uuid(), 'EACH', 'CASE',   'Case'),
  (gen_random_uuid(), 'EACH', 'PALLET', 'Pallet'),
  (gen_random_uuid(), 'EACH', 'BOX',    'Box'),
  (gen_random_uuid(), 'EACH', 'BAG',    'Bag'),
  (gen_random_uuid(), 'EACH', 'BUNDLE', 'Bundle'),
  -- WEIGHT
  (gen_random_uuid(), 'WEIGHT', 'KG', 'Kilogram'),
  (gen_random_uuid(), 'WEIGHT', 'G',  'Gram'),
  (gen_random_uuid(), 'WEIGHT', 'LB', 'Pound'),
  (gen_random_uuid(), 'WEIGHT', 'OZ', 'Ounce'),
  (gen_random_uuid(), 'WEIGHT', 'T',  'Metric Tonne'),
  -- VOLUME
  (gen_random_uuid(), 'VOLUME', 'L',     'Litre'),
  (gen_random_uuid(), 'VOLUME', 'ML',    'Millilitre'),
  (gen_random_uuid(), 'VOLUME', 'CL',    'Centilitre'),
  (gen_random_uuid(), 'VOLUME', 'FL_OZ', 'Fluid Ounce'),
  (gen_random_uuid(), 'VOLUME', 'GAL',   'Gallon (US)'),
  -- LENGTH
  (gen_random_uuid(), 'LENGTH', 'M',  'Metre'),
  (gen_random_uuid(), 'LENGTH', 'CM', 'Centimetre'),
  (gen_random_uuid(), 'LENGTH', 'MM', 'Millimetre'),
  (gen_random_uuid(), 'LENGTH', 'FT', 'Foot'),
  (gen_random_uuid(), 'LENGTH', 'IN', 'Inch'),
  -- TIME
  (gen_random_uuid(), 'TIME', 'DAY',   'Day'),
  (gen_random_uuid(), 'TIME', 'HOUR',  'Hour'),
  (gen_random_uuid(), 'TIME', 'WEEK',  'Week'),
  (gen_random_uuid(), 'TIME', 'MONTH', 'Month'),
  -- AREA
  (gen_random_uuid(), 'AREA', 'SQM',  'Square Metre'),
  (gen_random_uuid(), 'AREA', 'SQFT', 'Square Foot'),
  (gen_random_uuid(), 'AREA', 'HA',   'Hectare');

-- ── Seed: standard conversions (both directions) ────────────────────────────
INSERT INTO uom_standard_conversions (id, from_uom, to_uom, factor) VALUES
  -- EACH class (relative to EA)
  (gen_random_uuid(), 'PAIR',   'EA',     2),
  (gen_random_uuid(), 'EA',     'PAIR',   0.5),
  (gen_random_uuid(), 'DOZEN',  'EA',     12),
  (gen_random_uuid(), 'EA',     'DOZEN',  0.08333333),
  (gen_random_uuid(), 'SCORE',  'EA',     20),
  (gen_random_uuid(), 'EA',     'SCORE',  0.05),
  (gen_random_uuid(), 'GROSS',  'EA',     144),
  (gen_random_uuid(), 'EA',     'GROSS',  0.00694444),
  (gen_random_uuid(), 'CASE',   'EA',     24),
  (gen_random_uuid(), 'EA',     'CASE',   0.04166667),
  (gen_random_uuid(), 'PALLET', 'EA',     100),
  (gen_random_uuid(), 'EA',     'PALLET', 0.01),
  (gen_random_uuid(), 'BOX',    'EA',     6),
  (gen_random_uuid(), 'EA',     'BOX',    0.16666667),
  -- WEIGHT class (relative to KG)
  (gen_random_uuid(), 'G',  'KG', 0.001),
  (gen_random_uuid(), 'KG', 'G',  1000),
  (gen_random_uuid(), 'LB', 'KG', 0.45359237),
  (gen_random_uuid(), 'KG', 'LB', 2.20462262),
  (gen_random_uuid(), 'OZ', 'KG', 0.02834952),
  (gen_random_uuid(), 'KG', 'OZ', 35.27396195),
  (gen_random_uuid(), 'T',  'KG', 1000),
  (gen_random_uuid(), 'KG', 'T',  0.001),
  -- VOLUME class (relative to L)
  (gen_random_uuid(), 'ML',    'L', 0.001),
  (gen_random_uuid(), 'L',     'ML', 1000),
  (gen_random_uuid(), 'CL',    'L', 0.01),
  (gen_random_uuid(), 'L',     'CL', 100),
  (gen_random_uuid(), 'FL_OZ', 'L', 0.02957353),
  (gen_random_uuid(), 'L',     'FL_OZ', 33.81402265),
  (gen_random_uuid(), 'GAL',   'L', 3.78541178),
  (gen_random_uuid(), 'L',     'GAL', 0.26417205),
  -- LENGTH class (relative to M)
  (gen_random_uuid(), 'CM', 'M',  0.01),
  (gen_random_uuid(), 'M',  'CM', 100),
  (gen_random_uuid(), 'MM', 'M',  0.001),
  (gen_random_uuid(), 'M',  'MM', 1000),
  (gen_random_uuid(), 'FT', 'M',  0.3048),
  (gen_random_uuid(), 'M',  'FT', 3.28083990),
  (gen_random_uuid(), 'IN', 'M',  0.0254),
  (gen_random_uuid(), 'M',  'IN', 39.37007874),
  -- TIME class (relative to DAY)
  (gen_random_uuid(), 'HOUR',  'DAY',  0.04166667),
  (gen_random_uuid(), 'DAY',   'HOUR', 24),
  (gen_random_uuid(), 'WEEK',  'DAY',  7),
  (gen_random_uuid(), 'DAY',   'WEEK', 0.14285714),
  (gen_random_uuid(), 'MONTH', 'DAY',  30.4375),
  (gen_random_uuid(), 'DAY',   'MONTH', 0.03285421),
  -- AREA class (relative to SQM)
  (gen_random_uuid(), 'SQFT', 'SQM', 0.09290304),
  (gen_random_uuid(), 'SQM',  'SQFT', 10.76391042),
  (gen_random_uuid(), 'HA',   'SQM', 10000),
  (gen_random_uuid(), 'SQM',  'HA',  0.0001);
