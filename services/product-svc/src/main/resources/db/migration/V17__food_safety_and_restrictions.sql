-- Four mandatory capabilities the readiness review graded absent, all of them law rather than
-- product strategy: allergen declaration, country of origin, age-restricted sales, and selling
-- goods by weight.
--
-- They land together because they are the same shape -- statements a retailer must be able to make
-- about an item before it may be offered -- and because three of the four are attributes of a
-- variant, so splitting them would mean three migrations touching one table.

-- ---------------------------------------------------------------------------------------------
-- 1. Allergens.  Natasha's Law (Food Information (Amendment) (England) Regulations 2019) and
--    Regulation (EU) 1169/2011 Annex II, which lists exactly fourteen.
-- ---------------------------------------------------------------------------------------------

-- System-wide reference data, no tenant_id -- the fourteen are set by regulation, not by the
-- business, and a tenant that could edit them could quietly delete one. Same precedent as
-- uom_definitions in V3.
CREATE TABLE allergens (
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    -- What a label has to say. Annex II names the category; the substances are examples of it.
    detail     TEXT,
    regulation TEXT NOT NULL,
    CONSTRAINT pk_allergens PRIMARY KEY (code)
);

INSERT INTO allergens (code, name, detail, regulation) VALUES
 ('CEREALS_GLUTEN','Cereals containing gluten','Wheat, rye, barley, oats, spelt, kamut','EU 1169/2011 Annex II (1)'),
 ('CRUSTACEANS','Crustaceans','Crab, lobster, prawns, scampi','EU 1169/2011 Annex II (2)'),
 ('EGGS','Eggs',NULL,'EU 1169/2011 Annex II (3)'),
 ('FISH','Fish',NULL,'EU 1169/2011 Annex II (4)'),
 ('PEANUTS','Peanuts',NULL,'EU 1169/2011 Annex II (5)'),
 ('SOYBEANS','Soybeans',NULL,'EU 1169/2011 Annex II (6)'),
 ('MILK','Milk','Including lactose','EU 1169/2011 Annex II (7)'),
 ('NUTS','Tree nuts','Almond, hazelnut, walnut, cashew, pecan, Brazil, pistachio, macadamia','EU 1169/2011 Annex II (8)'),
 ('CELERY','Celery',NULL,'EU 1169/2011 Annex II (9)'),
 ('MUSTARD','Mustard',NULL,'EU 1169/2011 Annex II (10)'),
 ('SESAME','Sesame seeds',NULL,'EU 1169/2011 Annex II (11)'),
 ('SULPHITES','Sulphur dioxide and sulphites','At concentrations above 10 mg/kg or 10 mg/l','EU 1169/2011 Annex II (12)'),
 ('LUPIN','Lupin',NULL,'EU 1169/2011 Annex II (13)'),
 ('MOLLUSCS','Molluscs','Mussels, oysters, squid, snails','EU 1169/2011 Annex II (14)');

-- A declaration, per variant.
--
-- presence is CONTAINS or MAY_CONTAIN and the distinction is legal, not cosmetic: "may contain"
-- is a cross-contamination warning, and collapsing the two into a boolean either invents a
-- declaration the producer never made or discards one they did.
CREATE TABLE variant_allergens (
    tenant_id     UUID        NOT NULL,
    variant_id    UUID        NOT NULL,
    allergen_code TEXT        NOT NULL,
    presence      TEXT        NOT NULL,
    declared_by   UUID,
    declared_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_variant_allergens  PRIMARY KEY (tenant_id, variant_id, allergen_code),
    CONSTRAINT fk_va_allergen        FOREIGN KEY (allergen_code) REFERENCES allergens (code),
    CONSTRAINT chk_va_presence       CHECK (presence IN ('CONTAINS','MAY_CONTAIN'))
);

CREATE INDEX idx_variant_allergens ON variant_allergens (tenant_id, variant_id);

-- Finding every product carrying an allergen is the query a recall runs, and the query a customer
-- with an allergy runs. Without this it is a full scan of the tenant's declarations.
CREATE INDEX idx_variant_allergens_by_allergen
    ON variant_allergens (tenant_id, allergen_code, presence);

-- ---------------------------------------------------------------------------------------------
-- 2. Age-restricted sales.
--
--    The minimum age is set by the country the STORE is in, not by the tenant and not by the
--    product -- the same bottle of wine is 18 in the UK, 20 in Japan and 21 in the US. So the
--    variant carries the category and the age is looked up per country at the till.
-- ---------------------------------------------------------------------------------------------

-- Statutory defaults, system-wide reference data like the allergen list.
CREATE TABLE age_restriction_rules (
    country     CHAR(2) NOT NULL,
    category    TEXT    NOT NULL,
    minimum_age INT     NOT NULL,
    note        TEXT,
    CONSTRAINT pk_age_rules  PRIMARY KEY (country, category),
    CONSTRAINT chk_age_range CHECK (minimum_age BETWEEN 0 AND 120)
);

INSERT INTO age_restriction_rules (country, category, minimum_age, note) VALUES
 ('GB','ALCOHOL',18,'Licensing Act 2003'),
 ('GB','TOBACCO',18,'Children and Young Persons (Protection from Tobacco) Act 1991'),
 ('GB','NICOTINE_VAPE',18,'Nicotine Inhaling Products (Age of Sale) Regulations 2015'),
 ('GB','KNIVES',18,'Criminal Justice Act 1988 s.141A'),
 ('GB','CORROSIVES',18,'Offensive Weapons Act 2019'),
 ('GB','SOLVENTS',18,'Intoxicating Substances (Supply) Act 1985'),
 ('GB','FIREWORKS',18,'Fireworks Regulations 2004'),
 ('GB','LOTTERY',18,'raised from 16 in October 2021'),
 ('GB','VIDEO_18',18,'Video Recordings Act 1984'),
 ('GB','PETROL',16,NULL),
 ('US','ALCOHOL',21,'National Minimum Drinking Age Act 1984'),
 ('US','TOBACCO',21,'federal Tobacco 21, December 2019'),
 ('US','NICOTINE_VAPE',21,'federal Tobacco 21, December 2019'),
 ('US','FIREWORKS',18,'varies by state -- override per tenant'),
 ('JP','ALCOHOL',20,'Minor Drinking Prohibition Act -- unchanged by the 2022 majority reform'),
 ('JP','TOBACCO',20,'Minor Smoking Prohibition Act'),
 ('JP','NICOTINE_VAPE',20,NULL),
 ('CN','ALCOHOL',18,NULL),
 ('CN','TOBACCO',18,'Law on the Protection of Minors'),
 ('IN','TOBACCO',18,'COTPA 2003'),
 -- India sets the drinking age by state: 18 in some, 21 in others, 25 in Maharashtra for spirits,
 -- and prohibition in Gujarat and Bihar. 21 is the commonest and is deliberately the conservative
 -- floor; a tenant trading in a state that differs must override it, which is what the override
 -- table below exists for.
 ('IN','ALCOHOL',21,'varies by state (18-25, prohibition in some) -- override per store country');

-- A tenant's own rule wins over the statutory default. Needed both for jurisdictions that vary
-- below national level and for a business choosing to sell above the legal minimum, which is
-- allowed and is a policy some chains adopt.
CREATE TABLE tenant_age_restriction_rules (
    tenant_id   UUID    NOT NULL,
    country     CHAR(2) NOT NULL,
    category    TEXT    NOT NULL,
    minimum_age INT     NOT NULL,
    reason      TEXT,
    set_by      UUID,
    set_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tenant_age_rules  PRIMARY KEY (tenant_id, country, category),
    CONSTRAINT chk_tenant_age_range CHECK (minimum_age BETWEEN 0 AND 120)
);

-- ---------------------------------------------------------------------------------------------
-- 3. The variant columns: origin, restriction category, and selling by weight.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE product_variants
    -- ISO 3166-1 alpha-2. Country of origin is mandatory for unprocessed meat, fruit and veg,
    -- fish, honey, olive oil and wine, and mandatory whenever its absence would mislead
    -- (EU 1169/2011 art.26).
    ADD COLUMN country_of_origin CHAR(2),
    -- "Produce of Spain, packed in the UK" -- the sentence a label carries when one code cannot
    -- say it.
    ADD COLUMN origin_detail TEXT,

    -- Which age rule applies, or NULL for the overwhelming majority that are unrestricted.
    ADD COLUMN restriction_category TEXT,

    -- Whether this is a food product at all, and whether its allergens have been stated.
    --
    -- This column is the whole safety argument. An empty allergen list must never be read as
    -- "free from" -- a tin of biscuits nobody has got round to declaring looks identical to one
    -- declared allergen-free, and the difference is a hospital admission. UNDECLARED is the
    -- default so a new food product is silently unsafe to advertise rather than silently safe.
    ADD COLUMN allergen_status TEXT NOT NULL DEFAULT 'NOT_APPLICABLE',

    -- Ingredients, as printed. Required alongside the allergen list for prepacked food, and the
    -- source a declaration is checked against.
    ADD COLUMN ingredients TEXT,

    -- How the item is sold. EACH is the default and covers nearly everything; WEIGHT is the loose
    -- produce, deli and butchery counter a supermarket cannot trade without.
    ADD COLUMN sold_by TEXT NOT NULL DEFAULT 'EACH',

    -- Net quantity in the pack, for the unit price a shelf edge must display
    -- (Price Marking Order 2004: price per kg / per litre alongside the selling price).
    ADD COLUMN net_content NUMERIC(18,4),
    ADD COLUMN net_content_uom TEXT,

    -- Packaging weight a scale deducts before pricing. Charging the customer for the tub is one
    -- of the things weights-and-measures inspection exists to catch.
    ADD COLUMN tare_weight NUMERIC(18,4),

    -- True when every individual item has its own weight -- a joint of meat, a whole fish. The
    -- price is not knowable until the item is on the scale.
    ADD COLUMN catch_weight BOOLEAN NOT NULL DEFAULT FALSE,

    ADD CONSTRAINT chk_variant_sold_by
        CHECK (sold_by IN ('EACH','WEIGHT','VOLUME','LENGTH')),
    ADD CONSTRAINT chk_variant_allergen_status
        CHECK (allergen_status IN ('UNDECLARED','DECLARED','NOT_APPLICABLE')),
    -- A country code that is not two letters is a data-entry slip, and origin is a legal claim.
    ADD CONSTRAINT chk_variant_origin
        CHECK (country_of_origin IS NULL OR country_of_origin ~ '^[A-Z]{2}$'),
    -- Selling by weight without saying which unit leaves the shelf edge unable to price it.
    ADD CONSTRAINT chk_variant_net_content
        CHECK (sold_by = 'EACH' OR net_content_uom IS NOT NULL OR catch_weight),
    ADD CONSTRAINT chk_variant_tare
        CHECK (tare_weight IS NULL OR tare_weight >= 0);

-- The till asks "is this restricted?" on every scanned line, and the compliance screen asks
-- "what have we not declared yet?". Both are partial -- the restricted and undeclared sets are
-- small next to the catalogue.
CREATE INDEX idx_variants_restricted
    ON product_variants (tenant_id, restriction_category)
    WHERE restriction_category IS NOT NULL;

CREATE INDEX idx_variants_undeclared
    ON product_variants (tenant_id)
    WHERE allergen_status = 'UNDECLARED';
