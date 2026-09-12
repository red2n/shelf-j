-- 18.5: the fiscal regime a store trades under, and what it stamps on every legal receipt.
--
-- A gapless, hash-chained register (V16, V22) is what every fiscal regime asks a till to keep.
-- What differs by market is the stamp each document must also carry: in Germany a signature from a
-- certified security module (TSE) over the transaction (§146a AO, KassenSichV §2 and §6); in
-- Portugal an RSA signature over the document's own figures chained to the previous document
-- (Decreto-Lei 198/2012, Portaria 363/2010, Despacho 8632/2014). The regime is set per store
-- because a tenant trades in more than one country, and the stamp is stored on the document because
-- an inspector reads it off the document, years later, whatever the store's settings are by then.

-- Which regime each store is under, and the identity the regime's file names the business by.
-- NONE is the register alone — what every store was under before this migration.
CREATE TABLE fiscal_store_settings (
    tenant_id               UUID NOT NULL,
    store_id                UUID NOT NULL,
    regime                  TEXT NOT NULL,          -- NONE | DE_KASSENSICHV | PT_SAFT
    -- USt-IdNr or Steuernummer (DE), NIF (PT): what the file and the document name the business by.
    tax_registration_number TEXT,
    -- PT: the certificate number the AT issued this software, printed on every document as
    -- "Processado por programa certificado n.º NNNN/AT". Absent until the software is certified.
    certificate_number      TEXT,
    -- PT: the validation code the AT issued for this series; the ATCUD on each document is
    -- "<code>-<number>". "0" until the series is registered with the AT.
    series_validation_code  TEXT,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    CONSTRAINT pk_fiscal_store_settings PRIMARY KEY (tenant_id, store_id),
    CONSTRAINT chk_fiscal_regime CHECK (regime IN ('NONE', 'DE_KASSENSICHV', 'PT_SAFT'))
);

-- The security module a German store signs with. One per store: the law counts one per
-- Aufzeichnungssystem, and this platform's recording system is the store's register.
--
-- SIMULATED holds its own key here so that a stack with no certified device still runs and still
-- stamps every document — and is exactly why it is not a certified device: a TSE never exposes its
-- key. CLOUD names a device at a provider whose certificate covers the signing; nothing of its key
-- is here.
CREATE TABLE tse_devices (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    store_id            UUID NOT NULL,
    provider            TEXT NOT NULL,              -- SIMULATED | CLOUD
    -- The client id the device knows this register by: DSFinV-K's Z_KASSE_ID, KassenSichV's
    -- ClientID on the receipt.
    client_id           TEXT NOT NULL,
    serial_number       TEXT NOT NULL,              -- hex of SHA-256 over the public key, as BSI TR-03153 defines it
    public_key          TEXT NOT NULL,              -- base64 DER
    signature_algorithm TEXT NOT NULL,              -- ecdsa-plain-SHA256
    time_format         TEXT NOT NULL,              -- unixTime | utcTime | generalizedTime
    private_key         TEXT,                       -- SIMULATED only: base64 PKCS#8; NULL for a real device
    external_tss_id     TEXT,                       -- CLOUD: the provider's id for the device
    signature_counter   BIGINT NOT NULL DEFAULT 0,  -- SIMULATED: the device's own counters
    transaction_counter BIGINT NOT NULL DEFAULT 0,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    registered_by       UUID,
    CONSTRAINT uq_tse_device_store UNIQUE (tenant_id, store_id),
    CONSTRAINT chk_tse_provider CHECK (provider IN ('SIMULATED', 'CLOUD'))
);

-- The stamp on the document. Nullable because a document issued under NONE carries none, and a
-- document issued under DE_KASSENSICHV while the device was unreachable carries the failure
-- instead — KassenSichV lets the till keep selling and requires the outage to be recorded, which
-- is what DSFinV-K's TSE_TA_FEHLER column is for.
ALTER TABLE fiscal_receipts ADD COLUMN regime TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE fiscal_receipts ADD COLUMN tse_serial TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_client_id TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_transaction_number BIGINT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_signature_counter BIGINT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_signature TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_algorithm TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_public_key TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_time_format TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_started_at TIMESTAMPTZ;
ALTER TABLE fiscal_receipts ADD COLUMN tse_finished_at TIMESTAMPTZ;
ALTER TABLE fiscal_receipts ADD COLUMN tse_process_type TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_process_data TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_qr TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN tse_error TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN pt_invoice_no TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN pt_hash TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN pt_hash_control TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN pt_atcud TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN pt_certificate_number TEXT;

-- The VAT on each line, as the quote priced it. Both the German and the Portuguese file list
-- every document by VAT rate, and a basket of 19% and 7% lines cannot be split from the order's
-- one tax total. NULL for a line placed with server-side pricing off.
ALTER TABLE order_items ADD COLUMN vat_amount NUMERIC(18,4);

-- How each tender was paid. The German file lists every payment as cash or not, and the TSE
-- signs that split; the order's one payment_method cannot say how a cash-and-card sale divided.
ALTER TABLE order_payment_events ADD COLUMN method TEXT;
