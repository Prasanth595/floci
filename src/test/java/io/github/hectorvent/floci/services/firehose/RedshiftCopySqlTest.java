package io.github.hectorvent.floci.services.firehose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftCopySqlTest {

    @Test
    void buildsCopyWithOptionsAndNoIamRole() {
        String sql = RedshiftCopySql.build("events", null, "stage", "2026/10/19/f.gz",
                "json 'auto' gzip");

        assertEquals("COPY events FROM 's3://stage/2026/10/19/f.gz' json 'auto' gzip", sql);
    }

    @Test
    void tableAndColumnsAreKeptVerbatimSoRedshiftCaseFoldingApplies() {
        String sql = RedshiftCopySql.build("Public.MyEvents", "Id, \"Full Name\"", "stage", "k", null);

        assertEquals("COPY Public.MyEvents (Id, \"Full Name\") FROM 's3://stage/k'", sql);
    }

    @Test
    void quotedTableNameIsAccepted() {
        String sql = RedshiftCopySql.build("\"my schema\".\"t\"", null, "stage", "k", null);

        assertEquals("COPY \"my schema\".\"t\" FROM 's3://stage/k'", sql);
    }

    @Test
    void tableNameThatIsNotAnIdentifierIsRejected() {
        for (String table : new String[] {"t; DROP TABLE x", "a.b.c", "ev\"il", "t (id)", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RedshiftCopySql.build(table, null, "b", "k", null), table);
        }
    }

    @Test
    void columnThatIsNotAnIdentifierIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RedshiftCopySql.build("t", "id, name); DROP TABLE t; --", "b", "k", null));
    }

    @Test
    void statementSeparatorOutsideQuotesInCopyOptionsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RedshiftCopySql.build("t", null, "b", "k", "gzip; DROP TABLE t"));
    }

    @Test
    void semicolonInsideAQuotedCopyOptionValueIsAllowed() {
        String sql = RedshiftCopySql.build("t", null, "b", "k", "DELIMITER ';' CSV");

        assertEquals("COPY t FROM 's3://b/k' DELIMITER ';' CSV", sql);
    }

    @Test
    void separatorScanHandlesEscapedQuotes() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s;ok'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s' ; DROP"));
    }

    @Test
    void doubleQuotedValueMayContainASemicolon() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER \";\" CSV"));
    }

    @Test
    void escapeStringCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS E'\\'';DROP TABLE t;--'"));
    }

    @Test
    void quoteInsideADoubleQuotedIdentifierCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("\"a'b\"; DROP TABLE t; --'"));
    }

    @Test
    void escapeStringKeepsAnEscapedQuoteInsideTheLiteral() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS E'it\\'s;ok'"));
    }

    @Test
    void dollarQuotingAndCommentsAreRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $$'$$ gzip"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip -- trailing"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip /* x */"));
    }

    @Test
    void objectKeyThatTheProxyCouldNotParseIsRejected() {
        for (String key : new String[] {"my prefix/f.gz", "it's/f.gz", "say\"/f.gz"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RedshiftCopySql.build("t", null, "stage", key, null), key);
        }
    }
}
