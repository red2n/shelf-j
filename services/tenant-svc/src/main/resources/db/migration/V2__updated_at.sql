-- tenant-svc V2: add updated_at for audit / cache-invalidation on mutable entities.

ALTER TABLE tenants ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE stores  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE zones   ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
