-- SJ-D43. notification_log recorded who a message went to only as a recipient address, with no
-- customer or account id — so when a shop erased a customer, or a person deleted their account,
-- there was no way to find the messages sent to them, and every one survived.
--
-- subject_id is the customer (for a shop's message) or the account (for a platform message such as
-- WELCOME) the notification was about. redacted_at records that the recipient, subject and body
-- were erased; the row stays so the send itself remains accounted for.
--
-- Rows written before this migration have no subject and cannot be found by one.
ALTER TABLE notification_log
    ADD COLUMN subject_id  UUID,
    ADD COLUMN redacted_at TIMESTAMPTZ;

CREATE INDEX idx_notification_log_subject
    ON notification_log (subject_id) WHERE subject_id IS NOT NULL;
