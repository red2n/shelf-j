-- Return to vendor (readiness review 07.8): the movement that sends goods back to a supplier.
--
-- purchase-svc raises the return and the debit note; the stock leaves here, when ReturnedToVendor
-- is consumed — a signed movement of its own type against the return, so a supplier's goods going
-- back are never confused with a customer's return coming in (RETURN) or a write-off (ADJUST).
INSERT INTO transaction_source_types (id, tenant_id, code, description) VALUES
  ('01a09650-2b3c-7001-8f6a-2a6d1d9e5c11', NULL, 'RTV', 'Return to vendor')
ON CONFLICT DO NOTHING;
