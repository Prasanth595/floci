package io.github.hectorvent.floci.services.firehose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftCopySqlTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/r";

    @Test
    void buildsCopyWithRoleAndOptions() {
        String sql = RedshiftCopySql.build("events", null, "stage", "2026/10/19/f.gz",
                "arn:aws:iam::000000000000:role/firehose", "json 'auto' gzip");

        assertEquals("COPY events FROM 's3://stage/2026/10/19/f.gz' "
                + "IAM_ROLE 'arn:aws:iam::000000000000:role/firehose' json 'auto' gzip", sql);
    }

    @Test
    void tableAndColumnsAreKeptVerbatimSoRedshiftCaseFoldingApplies() {
        String sql = RedshiftCopySql.build("Public.MyEvents", "Id, \"Full Name\"", "stage", "k", ROLE, null);

        assertEquals("COPY Public.MyEvents (Id, \"Full Name\") FROM 's3://stage/k' IAM_ROLE '" + ROLE + "'", sql);
    }

    @Test
    void quotedTableNameIsAccepted() {
        String sql = RedshiftCopySql.build("\"my schema\".\"t\"", null, "stage", "k", ROLE, null);

        assertEquals("COPY \"my schema\".\"t\" FROM 's3://stage/k' IAM_ROLE '" + ROLE + "'", sql);
    }

    @Test
    void singleQuoteInTheObjectKeyIsEscaped() {
        String sql = RedshiftCopySql.build("t", null, "stage", "it's", ROLE, null);

        assertEquals("COPY t FROM 's3://stage/it''s' IAM_ROLE '" + ROLE + "'", sql);
    }

    @Test
    void tableNameThatIsNotAnIdentifierIsRejected() {
        for (String table : new String[] {"t; DROP TABLE x", "a.b.c", "ev\"il", "t (id)", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RedshiftCopySql.build(table, null, "b", "k", ROLE, null), table);
        }
    }

    @Test
    void columnThatIsNotAnIdentifierIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RedshiftCopySql.build("t", "id, name); DROP TABLE t; --", "b", "k", ROLE, null));
    }

    @Test
    void statementSeparatorOutsideQuotesInCopyOptionsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RedshiftCopySql.build("t", null, "b", "k", ROLE,
                "gzip; DROP TABLE t"));
    }

    @Test
    void semicolonInsideAQuotedCopyOptionValueIsAllowed() {
        String sql = RedshiftCopySql.build("t", null, "b", "k", ROLE, "DELIMITER ';' CSV");

        assertTrue(sql.endsWith("IAM_ROLE '" + ROLE + "' DELIMITER ';' CSV"), sql);
    }

    @Test
    void separatorScanHandlesEscapedQuotes() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s;ok'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s' ; DROP"));
    }
}
