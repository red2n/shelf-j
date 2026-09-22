-- The language a customer reads their messages in (13.x, message templates): ISO 639, lower case.
-- NULL until they say, when their shop's own language is used. Not personal data in itself, and
-- not erased with the rest of the record: a language says nothing about who someone is.
ALTER TABLE customers
    ADD COLUMN preferred_language TEXT,
    ADD CONSTRAINT chk_customers_preferred_language
        CHECK (preferred_language IS NULL OR preferred_language ~ '^[a-z]{2,3}$');
