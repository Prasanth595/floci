package io.github.hectorvent.floci.services.redshift;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SQL inspection for audit records, preserving quoted text and skipping nested comments. */
public final class RedshiftAuditSql {
    private RedshiftAuditSql() { }

    public record UserChange(String username, String action, String oldUsername) { }

    public record Savepoint(String operation, String name) { }

    private enum Kind { WORD, IDENTIFIER, STRING, SYMBOL }

    private record Token(String text, int start, int end, Kind kind) {
        boolean keyword(String keyword) {
            return kind == Kind.WORD && keyword.equalsIgnoreCase(text);
        }

        String identifier() {
            return kind == Kind.IDENTIFIER
                    ? text.substring(1, text.length() - 1).replace("\"\"", "\"") : text.toLowerCase(Locale.ROOT);
        }
    }

    public static String sanitize(String sql) {
        List<Token> tokens = tokens(sql);
        StringBuilder sanitized = new StringBuilder(sql.length());
        int copied = 0;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).keyword("PASSWORD")) {
                continue;
            }
            Token value = tokens.get(++i);
            if (value.kind() == Kind.SYMBOL) {
                continue;
            }
            int end = value.end();
            // PostgreSQL concatenates string literals separated by a newline, including comments.
            while (value.kind() == Kind.STRING && i + 1 < tokens.size() && tokens.get(i + 1).kind() == Kind.STRING) {
                end = tokens.get(++i).end();
            }
            sanitized.append(sql, copied, value.start()).append("'[REDACTED]'");
            copied = end;
        }
        return sanitized.append(sql, copied, sql.length()).toString();
    }

    public static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        int start = 0;
        boolean hasContent = false;
        for (Token token : tokens(sql)) {
            if (token.kind() == Kind.SYMBOL && ";".equals(token.text())) {
                if (hasContent) {
                    statements.add(sql.substring(start, token.start()));
                }
                start = token.end();
                hasContent = false;
            } else {
                hasContent = true;
            }
        }
        if (hasContent) {
            statements.add(sql.substring(start));
        }
        return statements;
    }

    public static List<UserChange> userChanges(String sql) {
        List<Token> tokens = tokens(sql);
        if (tokens.size() < 3 || !tokens.get(1).keyword("USER")) {
            return List.of();
        }
        Token command = tokens.getFirst();
        String action = command.keyword("CREATE") ? "Create"
                : command.keyword("ALTER") ? "Alter" : command.keyword("DROP") ? "Drop" : null;
        if (action == null) {
            return List.of();
        }
        int name = 2;
        if (tokens.get(name).keyword("IF")) {
            name++;
            if (name < tokens.size() && tokens.get(name).keyword("NOT")) {
                name++;
            }
            if (name >= tokens.size() || !tokens.get(name++).keyword("EXISTS")) {
                return List.of();
            }
        }
        if (name >= tokens.size()) {
            return List.of();
        }
        String username = tokens.get(name).identifier();
        if ("Alter".equals(action) && name + 3 < tokens.size()
                && tokens.get(name + 1).keyword("RENAME") && tokens.get(name + 2).keyword("TO")) {
            return List.of(new UserChange(tokens.get(name + 3).identifier(), "Rename", username));
        }
        List<UserChange> changes = new ArrayList<>();
        changes.add(new UserChange(username, action, null));
        if ("Drop".equals(action)) {
            for (int i = name + 1; i + 1 < tokens.size() && ",".equals(tokens.get(i).text()); i += 2) {
                changes.add(new UserChange(tokens.get(i + 1).identifier(), action, null));
            }
        }
        return changes;
    }

    public static Savepoint savepoint(String sql) {
        List<Token> tokens = tokens(sql);
        if (tokens.size() < 2) {
            return null;
        }
        int name = 1;
        String operation;
        if (tokens.getFirst().keyword("SAVEPOINT")) {
            operation = "SAVEPOINT";
        } else if (tokens.getFirst().keyword("RELEASE")) {
            operation = "RELEASE";
            if (tokens.get(name).keyword("SAVEPOINT")) {
                name++;
            }
        } else if (tokens.getFirst().keyword("ROLLBACK")) {
            operation = "ROLLBACK";
            if (tokens.get(name).keyword("WORK") || tokens.get(name).keyword("TRANSACTION")) {
                name++;
            }
            if (name >= tokens.size() || !tokens.get(name++).keyword("TO")) {
                return null;
            }
            if (name < tokens.size() && tokens.get(name).keyword("SAVEPOINT")) {
                name++;
            }
        } else {
            return null;
        }
        return name < tokens.size() ? new Savepoint(operation, tokens.get(name).identifier()) : null;
    }

    private static List<Token> tokens(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (sql.startsWith("--", i)) {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (sql.startsWith("/*", i)) {
                i = skipComment(sql, i);
            } else {
                int start = i;
                Kind kind;
                if ("eEnNbBxX".indexOf(c) >= 0 && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i = skipQuoted(sql, i + 1, '\'', c == 'e' || c == 'E');
                    kind = Kind.STRING;
                } else if ((c == 'u' || c == 'U') && sql.startsWith("&'", i + 1)) {
                    i = skipQuoted(sql, i + 2, '\'', false);
                    kind = Kind.STRING;
                } else if (c == '\'' || c == '"') {
                    i = skipQuoted(sql, i, c, false);
                    kind = c == '\'' ? Kind.STRING : Kind.IDENTIFIER;
                } else if (c == '$' && dollarDelimiter(sql, i) != null) {
                    String delimiter = dollarDelimiter(sql, i);
                    int end = sql.indexOf(delimiter, i + delimiter.length());
                    i = end < 0 ? sql.length() : end + delimiter.length();
                    kind = Kind.STRING;
                } else if (Character.isLetterOrDigit(c) || c == '_') {
                    i++;
                    while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                            || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                        i++;
                    }
                    kind = Kind.WORD;
                } else {
                    i++;
                    kind = Kind.SYMBOL;
                }
                tokens.add(new Token(sql.substring(start, i), start, i, kind));
            }
        }
        return tokens;
    }

    private static int skipComment(String sql, int start) {
        int depth = 1;
        int i = start + 2;
        while (i < sql.length() && depth > 0) {
            if (sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int skipQuoted(String sql, int start, char quote, boolean escapes) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i++);
            if (escapes && c == '\\' && i < sql.length()) {
                i++;
            } else if (c == quote) {
                if (i >= sql.length() || sql.charAt(i) != quote) {
                    return i;
                }
                i++;
            }
        }
        return i;
    }

    private static String dollarDelimiter(String sql, int start) {
        int i = start + 1;
        if (i < sql.length() && sql.charAt(i) == '$') {
            return "$$";
        }
        if (i >= sql.length() || !(Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            return null;
        }
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return i < sql.length() && sql.charAt(i) == '$' ? sql.substring(start, i + 1) : null;
    }
}
