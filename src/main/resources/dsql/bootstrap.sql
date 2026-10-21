-- What a cluster's PostgreSQL is before any client connects, as Aurora DSQL's database is (measured
-- on AWS, eu-west-1, 2026-10-16): the role admin, which owns the public schema, and repeatable read.
-- Installed by Floci as the superuser dbowner every time the database starts; every statement is
-- idempotent.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin') THEN
        CREATE ROLE admin WITH LOGIN CREATEROLE CREATEDB;
    END IF;
END
$$;
GRANT CREATE ON DATABASE postgres TO admin;
ALTER SCHEMA public OWNER TO admin;
ALTER DATABASE postgres SET default_transaction_isolation TO 'repeatable read';
