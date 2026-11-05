-- flagkit roles for one environment. ${p} is the table prefix (validated before substitution).
-- Run after schema.sql, as a superuser or a role that can create roles and change table owners.
--
--   flagkit_${p}reader  applications: SELECT on the flag tables only
--   flagkit_${p}writer  operators: write the flag tables, INSERT-only on audit
--   flagkit_${p}owner   owns the tables and runs schema changes
--
-- Logins are created by the DBA and granted a group role, for example:
--   CREATE ROLE app_orders LOGIN PASSWORD '...' IN ROLE flagkit_${p}reader;
--   CREATE ROLE alice      LOGIN PASSWORD '...' IN ROLE flagkit_${p}writer;
-- Dropping alice's login revokes her access; the audit trail keeps her name.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'flagkit_${p}reader') THEN
        CREATE ROLE flagkit_${p}reader NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'flagkit_${p}writer') THEN
        CREATE ROLE flagkit_${p}writer NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'flagkit_${p}owner') THEN
        CREATE ROLE flagkit_${p}owner NOLOGIN;
    END IF;
END
$$;

ALTER TABLE ${p}flag         OWNER TO flagkit_${p}owner;
ALTER TABLE ${p}prerequisite OWNER TO flagkit_${p}owner;
ALTER TABLE ${p}param        OWNER TO flagkit_${p}owner;
ALTER TABLE ${p}meta         OWNER TO flagkit_${p}owner;
ALTER TABLE ${p}audit        OWNER TO flagkit_${p}owner;

GRANT SELECT ON ${p}flag, ${p}prerequisite, ${p}param, ${p}meta TO flagkit_${p}reader;

GRANT SELECT, INSERT, UPDATE, DELETE ON ${p}flag, ${p}prerequisite, ${p}param TO flagkit_${p}writer;
-- UPDATE on meta is needed for SELECT ... FOR UPDATE (the write lock) and the revision bump.
GRANT SELECT, UPDATE ON ${p}meta TO flagkit_${p}writer;
GRANT SELECT ON ${p}audit TO flagkit_${p}writer;
-- Column-level INSERT: actor and occurred_at always take the database defaults.
GRANT INSERT (operation, flag_key, before_value, after_value) ON ${p}audit TO flagkit_${p}writer;
