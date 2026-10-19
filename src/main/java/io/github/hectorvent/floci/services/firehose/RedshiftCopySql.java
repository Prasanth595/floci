package io.github.hectorvent.floci.services.firehose;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the {@code COPY} statement Firehose runs for a delivered object. AWS pastes the table,
 * the column list and {@code CopyOptions} into the statement as given, so this does the same and
 * lets Redshift's own identifier folding apply: an unquoted name is lowercased, a quoted one is
 * kept. Instead of quoting, it refuses anything the wire proxy would not intercept as a COPY
 * (the same name and object key shapes as {@code CopyStatementParser}) and any statement
 * separator outside a quoted literal, which is what keeps the text from carrying a second
 * statement. The statement runs in simple query mode, where PostgreSQL executes every
 * {@code ;}-separated statement it receives, so the separator scan follows PostgreSQL's own
 * quoting rules rather than a simplified one.
 *
 * <p>No {@code IAM_ROLE} or credentials are added. AWS hands the cluster temporary credentials of the stream's
 * role; the wire proxy reads a staged object straight from Floci's S3 when the statement names no role, whereas
 * an {@code IAM_ROLE} would have to exist in IAM and be associated with the cluster.
 */
final class RedshiftCopySql {

    private static final String NAME = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")";
    private static final Pattern QUALIFIED_NAME = Pattern.compile(NAME + "(?:\\." + NAME + ")?");
    private static final Pattern SIMPLE_NAME = Pattern.compile(NAME);
    private static final Pattern BUCKET = Pattern.compile("[^/'\"\\s]+");
    private static final Pattern OBJECT_KEY = Pattern.compile("[^'\"\\s]*");
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$[A-Za-z_]*\\$");

    private RedshiftCopySql() {}

    static String build(String table, String columns, String bucket, String key, String copyOptions) {
        if (!isValidTableName(table)) {
            throw new IllegalArgumentException("DataTableName is not a table identifier: " + table);
        }
        if (!isValidColumnList(columns)) {
            throw new IllegalArgumentException("DataTableColumns is not a list of column identifiers: " + columns);
        }
        if (!BUCKET.matcher(bucket).matches() || !OBJECT_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("The staged object s3://" + bucket + "/" + key
                    + " has a name the COPY emulation cannot read: keys must not contain whitespace or quotes.");
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
        sql.append(" FROM 's3://").append(bucket).append('/').append(key).append('\'');
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

    /**
     * True when the text could carry a second statement: a {@code ;} outside a quoted literal, an
     * unterminated literal, a dollar-quoted string or a comment. Single-quoted strings honour
     * backslash escapes only in the {@code E'...'} form, double-quoted identifiers are skipped as a
     * whole, and a doubled quote stays inside its literal.
     */
    static boolean hasStatementSeparator(String text) {
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\'') {
                boolean escapeString = i > 0 && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E')
                        && (i == 1 || !isIdentifierChar(text.charAt(i - 2)));
                i = endOfQuoted(text, i + 1, '\'', escapeString);
            } else if (c == '"') {
                i = endOfQuoted(text, i + 1, '"', false);
            } else if (c == ';' || startsComment(text, i) || DOLLAR_QUOTE.matcher(text).region(i, length).lookingAt()) {
                return true;
            } else {
                i++;
            }
            if (i < 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsComment(String text, int i) {
        if (i + 1 >= text.length()) {
            return false;
        }
        char c = text.charAt(i);
        char next = text.charAt(i + 1);
        return (c == '-' && next == '-') || (c == '/' && next == '*');
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Index just past the closing quote, or -1 when the literal is never closed. */
    private static int endOfQuoted(String text, int from, char quote, boolean backslashEscapes) {
        int j = from;
        while (j < text.length()) {
            char ch = text.charAt(j);
            if (backslashEscapes && ch == '\\') {
                j += 2;
            } else if (ch == quote) {
                if (j + 1 < text.length() && text.charAt(j + 1) == quote) {
                    j += 2;
                } else {
                    return j + 1;
                }
            } else {
                j++;
            }
        }
        return -1;
    }
}
