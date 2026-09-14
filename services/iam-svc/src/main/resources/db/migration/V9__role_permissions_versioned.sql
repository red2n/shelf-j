-- A custom role's permissions are versioned by the role's own updated_at (20.10).
--
-- Two redefinitions of one role within a second can reach this service in either order — the
-- outbox relay used to key Kafka records per event rather than per aggregate — and the older must
-- not overwrite the newer. Each assignment row remembers the version its permissions came from;
-- RoleDefined applies only when it is newer, and a StaffAssigned made with a newer definition is
-- not undone by an older RoleDefined that arrives afterwards.
ALTER TABLE user_roles ADD COLUMN permissions_at TIMESTAMPTZ;
