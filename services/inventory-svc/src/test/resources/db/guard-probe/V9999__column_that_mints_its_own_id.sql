-- Test-only migration (DatabaseIdsIT): a column that fills in its own uuid. Applied on top of the real
-- migrations in a throwaway schema, so common-service's afterMigrate check must fail the migrate.
ALTER TABLE lot_actions ALTER COLUMN id SET DEFAULT gen_random_uuid();
