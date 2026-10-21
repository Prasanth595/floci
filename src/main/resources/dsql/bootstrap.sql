-- What a cluster's PostgreSQL is before any client connects, as Aurora DSQL's database is (measured
-- on AWS, eu-west-1, 2026-10-16): the role admin, which owns the public schema, repeatable read, and
-- the sys schema with the session's identity and AWS IAM GRANT. Installed by Floci as the superuser
-- dbowner every time the database starts; every statement is idempotent.

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

CREATE SCHEMA IF NOT EXISTS sys AUTHORIZATION dbowner;
GRANT USAGE ON SCHEMA sys TO PUBLIC;

-- ── The session ───────────────────────────────────────────────────────────────

-- Each connection's IAM principal and session id, written by Floci's endpoint before the client's
-- first statement, keyed by the backend serving it. Only the definer functions read it, so a client
-- cannot change what its session reports.
CREATE TABLE IF NOT EXISTS sys.session_identity (
    pid integer PRIMARY KEY,
    iam_session_user text NOT NULL,
    session_id text NOT NULL
);
REVOKE ALL ON sys.session_identity FROM PUBLIC;

-- name, as DSQL's answers it (measured: an ARN past 63 bytes is cut there).
CREATE OR REPLACE FUNCTION sys.iam_session_user() RETURNS name
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT iam_session_user::name FROM sys.session_identity WHERE pid = pg_backend_pid() $$;

CREATE OR REPLACE FUNCTION sys.current_session_id() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT session_id FROM sys.session_identity WHERE pid = pg_backend_pid() $$;

-- A statement DSQL refuses, raised with DSQL's SQLSTATE and message.
CREATE OR REPLACE FUNCTION sys._reject(state text, message text, detail text DEFAULT NULL) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF detail IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = state, MESSAGE = message;
    END IF;
    RAISE EXCEPTION USING ERRCODE = state, MESSAGE = message, DETAIL = detail;
END
$$;

-- ── AWS IAM GRANT and REVOKE: which IAM role may log in as which database role ─

CREATE TABLE IF NOT EXISTS sys.iam_identity (
    oid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    arn text COLLATE "C" NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS sys.iam_auth_member (
    member bigint NOT NULL REFERENCES sys.iam_identity (oid) ON DELETE CASCADE,
    roleid oid NOT NULL,
    grantor oid NOT NULL,
    PRIMARY KEY (member, roleid)
);

CREATE OR REPLACE VIEW sys.iam_pg_role_mappings AS
SELECT i.oid::oid AS iam_oid, i.arn, m.roleid AS pg_role_oid, a.rolname AS pg_role_name,
       m.grantor AS grantor_pg_role_oid, b.rolname AS grantor_pg_role_name
FROM sys.iam_identity i
JOIN sys.iam_auth_member m ON i.oid = m.member
JOIN pg_authid a ON a.oid = m.roleid
LEFT JOIN pg_authid b ON b.oid = m.grantor;
GRANT SELECT ON sys.iam_pg_role_mappings TO PUBLIC;

-- The role an IAM role may be trusted with: one that exists, is no superuser, and on which the caller
-- holds the ADMIN option, which admin holds on every role it created and on none other, itself
-- included.
CREATE OR REPLACE FUNCTION sys._trusted_role(role_name text, verb text, preposition text) RETURNS oid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    superuser boolean;
BEGIN
    SELECT oid, rolsuper INTO role_oid, superuser FROM pg_roles WHERE rolname = role_name;
    IF role_oid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42704', MESSAGE = format('role "%s" does not exist', role_name);
    END IF;
    IF superuser THEN
        RAISE EXCEPTION USING ERRCODE = '0LP01', MESSAGE = format('cannot establish trust with role "%s"', role_name),
            DETAIL = 'cannot GRANT/REVOKE IAM for roles with the SUPERUSER attribute';
    END IF;
    IF NOT pg_has_role(session_user, role_oid, 'MEMBER WITH ADMIN OPTION') THEN
        RAISE EXCEPTION USING ERRCODE = '42501',
            MESSAGE = format('permission denied to %s IAM trust with role "%s"', verb, role_name),
            DETAIL = format('Only roles with the ADMIN option on role "%s" may %s IAM trust %s this role.',
                            role_name, verb, preposition);
    END IF;
    RETURN role_oid;
END
$$;

-- The mappings of a role dropped where Floci could not refuse it (a DO block or a function body),
-- and the identities left with no mapping, go with the next grant or revoke.
CREATE OR REPLACE PROCEDURE sys._forget_dropped_roles()
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    DELETE FROM sys.iam_auth_member m WHERE NOT EXISTS (SELECT 1 FROM pg_authid a WHERE a.oid = m.roleid);
    DELETE FROM sys.iam_identity i WHERE NOT EXISTS (SELECT 1 FROM sys.iam_auth_member m WHERE m.member = i.oid);
$$;

CREATE OR REPLACE PROCEDURE sys.aws_iam_grant(role_name text, iam_arn text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    identity bigint;
    granted_by text;
BEGIN
    IF iam_arn !~ '^arn:aws[a-z-]*:iam::[0-9]{12}:(role|user)/.+' THEN
        RAISE EXCEPTION USING ERRCODE = '0P000', MESSAGE = 'IAM role must look like an IAM ARN';
    END IF;
    role_oid := sys._trusted_role(role_name, 'grant', 'to');
    CALL sys._forget_dropped_roles();
    INSERT INTO sys.iam_identity (arn) VALUES (iam_arn) ON CONFLICT (arn) DO NOTHING;
    SELECT oid INTO identity FROM sys.iam_identity WHERE arn = iam_arn;
    IF EXISTS (SELECT 1 FROM sys.iam_auth_member m WHERE m.member = identity AND m.roleid = role_oid) THEN
        SELECT coalesce(b.rolname::text, m.grantor::text) INTO granted_by FROM sys.iam_auth_member m
        LEFT JOIN pg_authid b ON b.oid = m.grantor
        WHERE m.member = identity AND m.roleid = role_oid;
        RAISE NOTICE 'IAM role "%" has already been granted access to role "%" by role "%"',
            iam_arn, role_name, granted_by;
        RETURN;
    END IF;
    INSERT INTO sys.iam_auth_member (member, roleid, grantor)
    VALUES (identity, role_oid, (SELECT oid FROM pg_roles WHERE rolname = session_user));
END
$$;

CREATE OR REPLACE PROCEDURE sys.aws_iam_revoke(role_name text, iam_arn text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    identity bigint;
BEGIN
    IF iam_arn !~ '^arn:aws[a-z-]*:iam::[0-9]{12}:(role|user)/.+' THEN
        RAISE EXCEPTION USING ERRCODE = '0P000', MESSAGE = 'IAM role must look like an IAM ARN';
    END IF;
    role_oid := sys._trusted_role(role_name, 'revoke', 'from');
    SELECT oid INTO identity FROM sys.iam_identity WHERE arn = iam_arn;
    IF identity IS NULL OR NOT EXISTS (SELECT 1 FROM sys.iam_auth_member WHERE member = identity AND roleid = role_oid) THEN
        RAISE EXCEPTION USING ERRCODE = '42704', MESSAGE = format('IAM role "%s" does not exist', iam_arn);
    END IF;
    DELETE FROM sys.iam_auth_member WHERE member = identity AND roleid = role_oid;
    CALL sys._forget_dropped_roles();
END
$$;

-- A role an IAM role or user is mapped to cannot be dropped, as on DSQL (measured): one detail line
-- per mapping, in the order they were granted.
CREATE OR REPLACE FUNCTION sys._trust_relationships(role_name text) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT string_agg(format('privileges for trust relationship of role %s with arn %s', role_name, i.arn),
                      E'\n' ORDER BY i.oid)
    FROM sys.iam_auth_member m
    JOIN sys.iam_identity i ON i.oid = m.member
    JOIN pg_roles r ON r.oid = m.roleid
    WHERE r.rolname = role_name
$$;

-- DROP ROLE, USER or GROUP, which Floci's endpoint hands here: refused for a mapped role, otherwise
-- run as the caller.
CREATE OR REPLACE PROCEDURE sys.drop_roles(role_names text[], missing_ok boolean)
LANGUAGE plpgsql AS $$
DECLARE
    role_name text;
    trust text;
BEGIN
    FOREACH role_name IN ARRAY role_names LOOP
        trust := sys._trust_relationships(role_name);
        IF trust IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '2BP01',
                MESSAGE = format('role "%s" cannot be dropped because some objects depend on it', role_name),
                DETAIL = trust;
        END IF;
    END LOOP;
    EXECUTE format('DROP ROLE %s%s', CASE WHEN missing_ok THEN 'IF EXISTS ' ELSE '' END,
                   (SELECT string_agg(quote_ident(name), ', ') FROM unnest(role_names) AS name));
END
$$;

REVOKE ALL ON sys.iam_identity, sys.iam_auth_member FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION sys._trusted_role(text, text, text) FROM PUBLIC;
REVOKE EXECUTE ON PROCEDURE sys._forget_dropped_roles() FROM PUBLIC;
