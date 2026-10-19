package io.github.hectorvent.floci.services.firehose;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the {@code COPY} statement Firehose runs for a delivered object. AWS pastes the table,
 * the column list and {@code CopyOptions} into the statement as given, so this does the same and
 * lets Redshift's own identifier folding apply: an unquoted name is lowercased, a quoted one is
 * kept. Instead of quoting, it refuses anything the wire proxy would not intercept as a COPY
 * (the same name shapes as {@code CopyStatementParser}) and any statement separator outside a
 * quoted literal, which is what keeps the text from carrying a second statement.
 */
final class RedshiftCopySql {

    private static final String NAME = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")";
    private static final Pattern QUALIFIED_NAME = Pattern.compile(NAME + "(?:\\." + NAME + ")?");
    private static final Pattern SIMPLE_NAME = Pattern.compile(NAME);

    private RedshiftCopySql() {}

    static String build(String table, String columns, String bucket, String key, String roleArn,
                        String copyOptions) {
        if (!isValidTableName(table)) {
            throw new IllegalArgumentException("DataTableName is not a table identifier: " + table);
        }
        if (!isValidColumnList(columns)) {
            throw new IllegalArgumentException("DataTableColumns is not a list of column identifiers: " + columns);
        }
        if (copyOptions != null && hasStatementSeparator(copyOptions)) {
            throw new IllegalArgumentException("CopyOptions must not contain a statement separator.");
        }
        StringBuilder sql = new StringBuilder("COPY ").append(table);
        if (columns != null && !columns.isBlank()) {
            List<String> trimmed = new ArrayList<>();
            for (String column : columns.split(",")) {
                trimmed.add(column.trim());
            }
            sql.append(" (").append(String.join(", ", trimmed)).append(')');
        }
        sql.append(" FROM 's3://").append(escapeLiteral(bucket + "/" + key)).append('\'');
        sql.append(" IAM_ROLE '").append(escapeLiteral(roleArn)).append('\'');
        if (copyOptions != null && !copyOptions.isBlank()) {
            sql.append(' ').append(copyOptions.trim());
        }
        return sql.toString();
    }

    static boolean isValidTableName(String table) {
        return table != null && QUALIFIED_NAME.matcher(table).matches();
    }

    /** An absent or blank list is valid: the proxy then discovers the table columns itself. */
    static boolean isValidColumnList(String columns) {
        if (columns == null || columns.isBlank()) {
            return true;
        }
        for (String column : columns.split(",", -1)) {
            if (!SIMPLE_NAME.matcher(column.trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    /** True when a {@code ;} appears outside a single-quoted literal; a doubled quote stays inside the literal. */
    static boolean hasStatementSeparator(String text) {
        boolean inLiteral = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (c == ';' && !inLiteral) {
                return true;
            }
        }
        return false;
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
