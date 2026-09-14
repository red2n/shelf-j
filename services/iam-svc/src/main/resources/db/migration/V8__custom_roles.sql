-- Custom roles and granular permissions (readiness review 20.10).
--
-- The five roles stay as they are: every user_roles row still names one of them, the token still
-- carries them, and every tier gate in the platform still reads them. What a tenant may now do is
-- define a role ON a tier that holds fewer of that tier's permissions, and assign staff to it.
--
--   role_code    the tenant's own code for the role the assignment was made with, e.g. SHIFT_LEAD;
--                NULL for a plain tier assignment
--   permissions  the permissions that role held when it was assigned or last redefined, as a
--                comma-separated list ('' for a role narrowed to nothing); NULL for a plain tier
--                assignment, which is judged by the tier's defaults
--
-- Kept beside the assignment rather than in a roles projection of its own so that a login reads
-- one table, and updated in place when tenant-svc announces a role redefined (RoleDefined). A
-- role in use cannot be deleted, so a row never outlives the code it names.
ALTER TABLE user_roles
    ADD COLUMN role_code   TEXT,
    ADD COLUMN permissions TEXT;

CREATE INDEX idx_user_roles_code ON user_roles (role_code) WHERE role_code IS NOT NULL;
