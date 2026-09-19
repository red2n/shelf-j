-- Completing two rows that were partial (07.18): assortment by store, and range review.
--
-- Both had a real half already. V13's product_stores records which stores carry a product, with the
-- sensible default that a product with no rows sells everywhere. V24's lifecycle launches, discontinues
-- and reinstates a line against a date. Neither is touched here.
--
-- What was missing in each case was the discipline around the data, and it is the same shape twice: a
-- decision with a date, a reason, and the comparison it was made against. Today a line is ranged or
-- de-listed store by store, on somebody's judgement, and nothing records why or what it was weighed
-- against. That is the gap.

-- ── clusters: range by a group of stores, not one at a time ─────────────────────────────────────────
--
-- The practical complaint about per-store assortment is not that it cannot express a range — it is that
-- expressing one costs a row per store. A chain ranges by type: city convenience, superstore, the ten
-- shops with a fish counter. A cluster names that group once so a decision can be taken against it.
--
-- A store may belong to several clusters, deliberately: "Scotland" and "has a bakery" are both true of
-- the same shop, and forcing a single grouping would make one of them unsayable.
CREATE TABLE store_clusters (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    code        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    note        TEXT,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_cluster_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX uq_store_clusters_code
    ON store_clusters (tenant_id, lower(code))
    WHERE status = 'ACTIVE';

-- store_id is tenant-svc's and carries no foreign key across services (golden rule #1).
CREATE TABLE store_cluster_members (
    cluster_id UUID        NOT NULL REFERENCES store_clusters (id) ON DELETE CASCADE,
    store_id   UUID        NOT NULL,
    tenant_id  UUID        NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_store_cluster_members PRIMARY KEY (cluster_id, store_id)
);

CREATE INDEX idx_cluster_members_store ON store_cluster_members (tenant_id, store_id);

-- ── assortment changes: the decision, dated, with a reason ──────────────────────────────────────────
--
-- product_stores stays exactly as it is: the range as it stands today, which is what the till and the
-- storefront ask. This table is the decision log in front of it — append-only, because the point is to
-- be able to answer "who took this line out of the Scottish shops, when, and why" months later, and an
-- edited row cannot.
--
-- Two things follow from having dates. A change can be recorded BEFORE it takes effect, which is how a
-- range is planned rather than typed on the morning it happens; and applying it is a separate step, so
-- the log is the intent and product_stores is the state. `applied_at` is what distinguishes them.
CREATE TABLE assortment_changes (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    product_id     UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- Exactly one of these: a change is aimed at one store or at a cluster of them.
    store_id       UUID,
    cluster_id     UUID        REFERENCES store_clusters (id),
    action         TEXT        NOT NULL,
    effective_from DATE        NOT NULL,
    -- Why. Required, and that is the point of the table: a de-list with no reason is the thing this row
    -- exists to stop being possible.
    reason         TEXT        NOT NULL,
    decided_by     UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    -- When the change was actually pushed into product_stores. Null until then.
    applied_at     TIMESTAMPTZ,
    -- The review that produced it, when it came from one rather than from a single decision.
    review_id      UUID,

    CONSTRAINT ck_assortment_action CHECK (action IN ('LIST', 'DELIST')),
    CONSTRAINT ck_assortment_target CHECK (
        (store_id IS NOT NULL AND cluster_id IS NULL)
        OR (store_id IS NULL AND cluster_id IS NOT NULL)
    ),
    CONSTRAINT ck_assortment_reason CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX idx_assortment_changes_product
    ON assortment_changes (tenant_id, product_id, effective_from DESC);
-- The sweep that applies what is due reads this: everything dated on or before today, not yet applied.
CREATE INDEX idx_assortment_changes_pending
    ON assortment_changes (tenant_id, effective_from)
    WHERE applied_at IS NULL;

-- ── range review: the comparison behind an add or a drop ────────────────────────────────────────────
--
-- The half that was missing. A review looks at one category over a period, ranks its lines on what they
-- actually did, and produces the decisions a buyer signs off. Its value is not the decision — the
-- lifecycle could already launch and discontinue — it is that the figures the decision was taken on are
-- kept beside it. "We de-listed this because it was bottom of the category on margin, and here is the
-- number" is a different thing from "somebody de-listed this".
CREATE TABLE range_reviews (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    category_id UUID        NOT NULL REFERENCES categories (id),
    name        TEXT        NOT NULL,
    -- The trading period the figures cover. Inclusive of from, exclusive of to, as every period here is.
    period_from DATE        NOT NULL,
    period_to   DATE        NOT NULL,
    status      TEXT        NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    decided_at  TIMESTAMPTZ,
    decided_by  UUID,

    CONSTRAINT ck_review_status CHECK (status IN ('OPEN', 'DECIDED', 'ABANDONED')),
    CONSTRAINT ck_review_period CHECK (period_to > period_from),
    CONSTRAINT ck_review_decided CHECK ((status = 'DECIDED') = (decided_at IS NOT NULL))
);

CREATE INDEX idx_range_reviews_category
    ON range_reviews (tenant_id, category_id, period_from DESC);

-- One line under review, with the figures it was judged on.
--
-- The figures are a SNAPSHOT supplied when the line is added, not a live read. Deliberate: sales and
-- margin belong to order-svc and reporting-svc, and product-svc does not read another service's tables.
-- A snapshot is also the more useful record — the decision was taken on the numbers as they stood, and
-- a report re-run next year would show different ones and make the decision look arbitrary.
CREATE TABLE range_review_lines (
    id            UUID    PRIMARY KEY,
    tenant_id     UUID    NOT NULL,
    review_id     UUID    NOT NULL REFERENCES range_reviews (id) ON DELETE CASCADE,
    variant_id    UUID    NOT NULL REFERENCES product_variants (id),
    -- What it did over the period, as recorded at review time.
    units_sold    NUMERIC(14, 3),
    revenue       NUMERIC(14, 2),
    margin        NUMERIC(14, 2),
    currency      CHAR(3),
    -- Where it came in the category on whatever the buyer ranked by. 1 is best.
    rank_in_category INTEGER,
    -- KEEP, DELIST, INTRODUCE — or null while the review is still being read.
    decision      TEXT,
    decision_note TEXT,
    -- True when the line is protected from a de-list because the business owns the brand. Kept on the
    -- row rather than looked up later: own-brand status can change, and the review should show the
    -- reason as it applied on the day.
    own_brand     BOOLEAN NOT NULL,

    CONSTRAINT ck_review_line_decision CHECK (
        decision IS NULL OR decision IN ('KEEP', 'DELIST', 'INTRODUCE')
    ),
    CONSTRAINT ck_review_line_rank CHECK (rank_in_category IS NULL OR rank_in_category >= 1),
    -- A currency is needed exactly when there is money to put it against.
    CONSTRAINT ck_review_line_currency CHECK (
        (revenue IS NULL AND margin IS NULL) = (currency IS NULL)
    )
);

-- A variant appears once in a review: two rows for one line would be two rankings of the same thing.
CREATE UNIQUE INDEX uq_review_line ON range_review_lines (review_id, variant_id);
CREATE INDEX idx_review_lines_review ON range_review_lines (review_id, rank_in_category);

-- The link from a decision to the change it produced, so a de-listed line traces back to the review and
-- the figures. Added now rather than left implicit: without it the two tables are two stories.
ALTER TABLE assortment_changes
    ADD CONSTRAINT fk_assortment_change_review
    FOREIGN KEY (review_id) REFERENCES range_reviews (id);

COMMENT ON TABLE store_clusters IS
    'A named group of stores to range against. A store may belong to several: groupings overlap.';
COMMENT ON TABLE assortment_changes IS
    'The dated, reasoned decision log in front of product_stores. Append-only; applied_at marks the push.';
COMMENT ON TABLE range_reviews IS
    'A category reviewed over a period. Its worth is the figures kept beside each decision.';
COMMENT ON COLUMN range_review_lines.units_sold IS
    'A snapshot at review time, not a live read: product-svc does not read another service''s tables.';
