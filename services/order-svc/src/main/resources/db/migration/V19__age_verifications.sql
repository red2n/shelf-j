-- The due-diligence record behind an age-restricted sale.
--
--   Licensing Act 2003 s.146 / s.139        selling alcohol to a person under 18 is an offence; the
--                                            defence is that all reasonable precautions were taken
--                                            and all due diligence exercised
--   Children and Young Persons Act 1933 s.7  tobacco; the same "reasonable precautions" defence
--   Offensive Weapons Act 2019 s.1           corrosives; knives under CJA 1988 s.141A
--
-- The till has asked before every restricted sale since aca0577. What it never did was write down
-- what happened next. A prompt that nobody can prove was answered protects nobody: the defence is a
-- record — which item, which age, which country's rule, who checked, what they saw, and every time
-- a sale was refused. That last part is what a licensing officer asks for first, because a shop
-- that has never refused anyone has not been checking.
--
-- Append-only, like the POS journal: a check that was made is never edited or deleted. A wrong
-- entry is corrected by a further entry, not by rewriting the first.

CREATE TABLE age_verifications (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    store_id       UUID        NOT NULL,
    -- The member of staff who made the check, from the token; null only for a record replayed
    -- from an offline till whose session had no principal.
    cashier_id     UUID,
    pos_session_id UUID,
    variant_id     UUID        NOT NULL,
    -- Copied from product-svc's answer at the moment of the check, so the record says what the
    -- rule WAS, not what it later became.
    category       TEXT        NOT NULL,
    minimum_age    INTEGER     NOT NULL,
    country        TEXT        NOT NULL,
    store_policy   BOOLEAN     NOT NULL DEFAULT FALSE,
    outcome        TEXT        NOT NULL,
    -- Why a sale was refused. Required on a refusal, absent on a pass.
    reason         TEXT,
    -- What was shown when the sale went ahead. Optional: the law asks for reasonable precautions,
    -- not a document type, but a shop running Challenge 25 wants it.
    id_type        TEXT,
    -- The sale the check belonged to, once there was one; a refusal usually has none.
    order_id       UUID,
    checked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_age_outcome CHECK (outcome IN ('PASSED', 'REFUSED')),
    CONSTRAINT chk_age_reason CHECK (reason IS NULL OR reason IN (
        'UNDER_AGE', 'NO_ID', 'ID_REJECTED', 'PROXY_SALE', 'OTHER')),
    CONSTRAINT chk_age_reason_on_refusal CHECK ((outcome = 'REFUSED') = (reason IS NOT NULL)),
    CONSTRAINT chk_age_id_type CHECK (id_type IS NULL OR id_type IN (
        'PASSPORT', 'DRIVING_LICENCE', 'PASS_CARD', 'MILITARY_ID', 'NATIONAL_ID', 'OTHER')),
    CONSTRAINT chk_age_id_type_on_pass CHECK (id_type IS NULL OR outcome = 'PASSED'),
    CONSTRAINT chk_age_minimum CHECK (minimum_age BETWEEN 1 AND 99),
    CONSTRAINT chk_age_country CHECK (country ~ '^[A-Z]{2}$')
);
-- The register a licensing officer reads: one store, a date range, newest first.
CREATE INDEX idx_age_verifications_store
    ON age_verifications (tenant_id, store_id, checked_at DESC, id DESC);
-- "How many refusals this quarter, and why" across the tenant.
CREATE INDEX idx_age_verifications_outcome
    ON age_verifications (tenant_id, outcome, checked_at DESC);
