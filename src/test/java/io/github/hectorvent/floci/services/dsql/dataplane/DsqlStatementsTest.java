package io.github.hectorvent.floci.services.dsql.dataplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DsqlStatementsTest {

    @Test
    void awsIamGrantAndRevokeBecomeCallsWithDsqlsCommandTags() {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite(
                "AWS IAM GRANT App_Role TO 'arn:aws:iam::123456789012:role/app';"
                        + " aws iam revoke \"Mixed\" from 'arn:aws:iam::123456789012:role/o''x'");

        assertEquals("CALL sys.aws_iam_grant('app_role', 'arn:aws:iam::123456789012:role/app')",
                statements.get(0).sql());
        assertEquals("AWS IAM GRANT", statements.get(0).commandTag());
        assertEquals("CALL sys.aws_iam_revoke('Mixed', 'arn:aws:iam::123456789012:role/o''x')",
                statements.get(1).sql());
        assertEquals("AWS IAM REVOKE", statements.get(1).commandTag());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SET dsql.x = 1|dsql.x",
        "SET LOCAL dsql.row_count TO 0|dsql.row_count",
        "RESET dsql.x|dsql.x",
        "SELECT set_config('dsql.internal', 'on', false)|dsql.internal",
        "SELECT 1, set_config('dsql.x', '1', true) FROM t|dsql.x",
        "ALTER ROLE admin SET dsql.x = 1|dsql.x",
        "ALTER ROLE admin IN DATABASE postgres RESET dsql.x|dsql.x",
        "CREATE FUNCTION f() RETURNS int LANGUAGE sql SET dsql.x = 1 AS 'SELECT 1'|dsql.x",
        "SET \"dsql\".x = 1|dsql.x",
        "SELECT \"set_config\"('dsql.x', '1', false)|dsql.x",
    })
    void theDsqlPrefixIsReservedOnEveryChannel(String sql, String name) {
        assertEquals(DsqlStatements.reject("42602", "invalid configuration parameter name \"" + name + "\"",
                "\"dsql\" is a reserved prefix."), DsqlStatements.rewrite(sql).get(0).sql());
    }

    @Test
    void showingADsqlParameterFindsNone() {
        assertEquals(DsqlStatements.reject("42704", "unrecognized configuration parameter \"dsql.session_id\"", null),
                DsqlStatements.rewrite("SHOW dsql.session_id").get(0).sql());
    }

    @Test
    void setConfigOfAnotherDottedParameterIsNotSupportedThoughSetIs() {
        assertEquals(DsqlStatements.reject("0A000", "setting configuration parameter \"myapp.foo\" not supported", null),
                DsqlStatements.rewrite("SELECT set_config('myapp.foo', 'x', false)").get(0).sql());
        assertEquals("SET myapp.foo = 'x'", DsqlStatements.rewrite("SET myapp.foo = 'x'").get(0).sql());
        assertEquals("SELECT set_config('search_path', 'public', false)",
                DsqlStatements.rewrite("SELECT set_config('search_path', 'public', false)").get(0).sql());
    }

    @Test
    void dropRoleBecomesTheCallThatRefusesAMappedRole() {
        assertEquals("CALL sys.drop_roles(ARRAY['app_role', 'Mixed']::text[], false)",
                DsqlStatements.rewrite("DROP ROLE App_Role, \"Mixed\"").get(0).sql());
        DsqlStatements.Statement user = DsqlStatements.rewrite("drop user if exists u").get(0);
        assertEquals("CALL sys.drop_roles(ARRAY['u']::text[], true)", user.sql());
        assertEquals("DROP ROLE", user.commandTag());
        assertEquals("DROP ROLE", DsqlStatements.rewrite("DROP GROUP g").get(0).commandTag());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SET search_path TO probe",
        "DROP ROLE CURRENT_USER",
        "DROP ROLE IF EXISTS",
        "DROP ROLE a b",
        "DROP ROLE a,",
        "SELECT 'AWS IAM GRANT a TO b' AS text",
        "CREATE INDEX ON t (n)",
        "TRUNCATE t",
    })
    void whatIsNotDsqlsOwnSyntaxPassesUnchanged(String sql) {
        assertEquals(sql, DsqlStatements.rewrite(sql).get(0).sql());
    }

    @Test
    void aQueryWithNothingToRewriteGoesToTheDatabaseExactlyAsSent() {
        String query = "-- comment\nSELECT 1;\n/* two */ SELECT 'a;b';";

        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query);

        assertEquals(query, rewritten.sql());
        assertEquals(false, rewritten.overridesTags());
    }

    @Test
    void aQueryWithDsqlSyntaxIsRewrittenStatementByStatementWithItsTags() {
        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(
                "SELECT 1; AWS IAM GRANT app TO 'arn:aws:iam::123456789012:role/app'");

        assertEquals("SELECT 1;\nCALL sys.aws_iam_grant('app', 'arn:aws:iam::123456789012:role/app')",
                rewritten.sql());
        assertEquals(Arrays.asList(null, "AWS IAM GRANT"), rewritten.commandTags());
        assertEquals(true, rewritten.overridesTags());
    }

    @Test
    void statementsAreSplitOutsideStringsCommentsAndDollarQuotes() {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite(
                "SELECT 'a;b'; /* ; */ SELECT $f$ ; $f$ -- ;\n; SELECT \"x;y\"");

        assertEquals(3, statements.size());
        assertEquals("SELECT 'a;b'", statements.get(0).sql());
        assertEquals("SELECT $f$ ; $f$", statements.get(1).sql());
        assertEquals("SELECT \"x;y\"", statements.get(2).sql());
    }
}
