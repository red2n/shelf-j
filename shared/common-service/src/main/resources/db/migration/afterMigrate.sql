-- Flyway runs this after every migrate, in every service (it ships in common-service, on the same
-- classpath location as each service's own migrations).
--
-- Shelf-J mints every id in Java with Ids.newId() (UUIDv7). A column that fills in its own uuid
-- hands out another version the moment an insert leaves the id out, so migrating fails if one
-- exists in this service's schema. Remove the DEFAULT and bind Ids.newId() instead.
--
-- FlywayRunner logs a failed migrate as a warning and keeps the service running, so what stops a
-- violation reaching main is the same check in common-test's PostgresSupport.stop().
DO
$$
DECLARE
    offenders TEXT;
BEGIN
    SELECT string_agg(format('%I.%I DEFAULT %s', table_name, column_name, column_default), ', ')
    INTO offenders
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND (column_default ILIKE '%gen_random_uuid%'
        OR column_default ILIKE '%uuid_generate_v%'
        OR column_default ILIKE '%uuid_v7%');
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'columns in schema % generate their own ids: %. Mint ids with Ids.newId().',
            current_schema(), offenders;
    END IF;
END
$$;
