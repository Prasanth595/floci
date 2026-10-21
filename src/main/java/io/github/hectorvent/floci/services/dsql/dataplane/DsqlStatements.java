package io.github.hectorvent.floci.services.dsql.dataplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The statements of a query as Aurora DSQL reads them. DSQL's own syntax ({@code AWS IAM GRANT}
 * and {@code REVOKE}) becomes the calls into the cluster's {@code sys} schema that implement it,
 * and a statement DSQL refuses becomes a call to {@code sys._reject} with the SQLSTATE and message
 * DSQL answers (measured on AWS, eu-west-1, 2026-10-16 and 2026-10-20), so the database raises the
 * error and the transaction fails as DSQL's does.
 */
public final class DsqlStatements {

    private static final String RESERVED_PREFIX = "dsql.";

    private DsqlStatements() {
    }

    /** One statement of a query, and the command tag a client should see for it when it differs. */
    public record Statement(String sql, String commandTag) {
    }

    /** The statements of {@code query}, each rewritten as DSQL reads it, in order. */
    public static List<Statement> rewrite(String query) {
        List<Statement> statements = new ArrayList<>();
        for (List<Token> tokens : split(tokenize(query))) {
            if (tokens.isEmpty()) {
                continue;
            }
            String text = query.substring(tokens.get(0).start(), tokens.get(tokens.size() - 1).end());
            statements.add(rewrite(text, tokens));
        }
        return statements;
    }

    /**
     * A query as the database runs it, and the command tag each of its statements shows, null where
     * the database's own is right.
     */
    public record Query(String sql, List<String> commandTags) {

        /** Whether any statement shows a command tag other than the database's. */
        public boolean overridesTags() {
            return commandTags.stream().anyMatch(tag -> tag != null);
        }
    }

    /**
     * The whole query as the database runs it: the statements rewritten and joined, or, when none
     * needed rewriting, the query exactly as the client sent it.
     */
    public static Query rewriteQuery(String query) {
        List<String> parts = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        boolean changed = false;
        for (List<Token> tokens : split(tokenize(query))) {
            if (tokens.isEmpty()) {
                continue;
            }
            String text = query.substring(tokens.get(0).start(), tokens.get(tokens.size() - 1).end());
            Statement statement = rewrite(text, tokens);
            changed |= statement.commandTag() != null || !statement.sql().equals(text);
            parts.add(statement.sql());
            tags.add(statement.commandTag());
        }
        return new Query(changed ? String.join(";\n", parts) : query, tags);
    }

    /** {@code sys._reject} raising {@code state} with {@code message}, as a statement. */
    public static String reject(String state, String message, String detail) {
        return "SELECT sys._reject(" + literal(state) + ", " + literal(message)
                + (detail == null ? "" : ", " + literal(detail)) + ")";
    }

    private static Statement rewrite(String text, List<Token> tokens) {
        String first = word(tokens, 0);
        String second = word(tokens, 1);
        String third = word(tokens, 2);

        if ("AWS".equals(first) && "IAM".equals(second) && ("GRANT".equals(third) || "REVOKE".equals(third))) {
            Statement iam = iamGrant(tokens, "GRANT".equals(third));
            if (iam != null) {
                return iam;
            }
        }
        Statement setting = setting(tokens, first);
        if (setting != null) {
            return setting;
        }
        return new Statement(text, null);
    }

    private static Statement iamGrant(List<Token> tokens, boolean grant) {
        if (tokens.size() != 6 || tokens.get(5).type() != Type.STRING) {
            return null;
        }
        String preposition = word(tokens, 4);
        if (!(grant ? "TO" : "FROM").equals(preposition)) {
            return null;
        }
        Token role = tokens.get(3);
        String roleName = switch (role.type()) {
            case WORD -> role.lower();
            case QUOTED_IDENTIFIER -> role.value();
            default -> null;
        };
        if (roleName == null) {
            return null;
        }
        String call = "CALL sys.aws_iam_" + (grant ? "grant" : "revoke") + "(" + literal(roleName) + ", "
                + literal(tokens.get(5).value()) + ")";
        return new Statement(call, grant ? "AWS IAM GRANT" : "AWS IAM REVOKE");
    }

    /**
     * DSQL reserves the {@code dsql} prefix as PostgreSQL reserves an extension's (measured,
     * eu-west-1, 2026-10-20): setting or resetting a {@code dsql.} parameter, with {@code SET} or
     * {@code RESET} in any statement or with {@code set_config}, is {@code 42602}, and showing one
     * {@code 42704}, as for a parameter that does not exist. {@code set_config} of any other dotted
     * parameter named in the statement is not supported, though {@code SET} of one is.
     */
    private static Statement setting(List<Token> tokens, String first) {
        if ("SHOW".equals(first)) {
            String name = parameterName(tokens, 1);
            if (name.startsWith(RESERVED_PREFIX)) {
                return rejected("42704", "unrecognized configuration parameter \"" + name + "\"");
            }
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String word = word(tokens, i);
            if ("SET".equals(word) || "RESET".equals(word)) {
                int at = "SESSION".equals(word(tokens, i + 1)) || "LOCAL".equals(word(tokens, i + 1)) ? i + 2 : i + 1;
                String name = parameterName(tokens, at);
                if (name.startsWith(RESERVED_PREFIX)) {
                    return reservedPrefix(name);
                }
            }
            if ("SET_CONFIG".equals(word) && i + 2 < tokens.size() && "(".equals(tokens.get(i + 1).text())
                    && tokens.get(i + 2).type() == Type.STRING) {
                String name = tokens.get(i + 2).value();
                if (name.startsWith(RESERVED_PREFIX)) {
                    return reservedPrefix(name);
                }
                if (name.contains(".")) {
                    return rejected("0A000", "setting configuration parameter \"" + name + "\" not supported");
                }
            }
        }
        return null;
    }

    private static Statement reservedPrefix(String name) {
        return new Statement(reject("42602", "invalid configuration parameter name \"" + name + "\"",
                "\"dsql\" is a reserved prefix."), null);
    }

    /** The parameter named from {@code at} on, its parts joined by dots, as PostgreSQL folds its case. */
    private static String parameterName(List<Token> tokens, int at) {
        StringBuilder name = new StringBuilder();
        for (int i = at; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == Type.WORD || token.type() == Type.QUOTED_IDENTIFIER) {
                name.append(identifier(token));
            } else if (".".equals(token.text()) && token.type() == Type.SYMBOL && name.length() > 0) {
                name.append('.');
                continue;
            } else {
                break;
            }
            if (i + 1 >= tokens.size() || !".".equals(tokens.get(i + 1).text())) {
                break;
            }
        }
        return name.toString();
    }

    private static Statement rejected(String state, String message) {
        return new Statement(reject(state, message, null), null);
    }

    // ── Tokens ────────────────────────────────────────────────────────────────

    enum Type { WORD, QUOTED_IDENTIFIER, STRING, NUMBER, SYMBOL }

    record Token(Type type, String text, String value, int start, int end) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
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
            } else if (c == '\'' || ((c == 'E' || c == 'e') && i + 1 < n && sql.charAt(i + 1) == '\'')) {
                boolean escapes = c != '\'';
                int start = i;
                i += escapes ? 2 : 1;
                StringBuilder value = new StringBuilder();
                while (i < n) {
                    char d = sql.charAt(i);
                    if (escapes && d == '\\' && i + 1 < n) {
                        value.append(sql.charAt(i + 1));
                        i += 2;
                    } else if (d == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        value.append('\'');
                        i += 2;
                    } else if (d == '\'') {
                        i++;
                        break;
                    } else {
                        value.append(d);
                        i++;
                    }
                }
                tokens.add(new Token(Type.STRING, sql.substring(start, i), value.toString(), start, i));
            } else if (c == '"') {
                int start = i++;
                StringBuilder value = new StringBuilder();
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '"' && i + 1 < n && sql.charAt(i + 1) == '"') {
                        value.append('"');
                        i += 2;
                    } else if (d == '"') {
                        i++;
                        break;
                    } else {
                        value.append(d);
                        i++;
                    }
                }
                tokens.add(new Token(Type.QUOTED_IDENTIFIER, sql.substring(start, i), value.toString(), start, i));
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                int end = close < 0 ? n : close + tag.length();
                String value = sql.substring(tagEnd, close < 0 ? n : close);
                tokens.add(new Token(Type.STRING, sql.substring(i, end), value, i, end));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                tokens.add(new Token(Type.WORD, sql.substring(start, i), null, start, i));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Type.NUMBER, sql.substring(start, i), null, start, i));
            } else {
                tokens.add(new Token(Type.SYMBOL, String.valueOf(c), null, i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    private static int dollarTagEnd(String sql, int at) {
        int i = at + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            if (i == at + 1 && Character.isDigit(sql.charAt(i))) {
                return -1;
            }
            i++;
        }
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
    }

    static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token token : tokens) {
            if ("(".equals(token.text()) && token.type() == Type.SYMBOL) {
                depth++;
            } else if (")".equals(token.text()) && token.type() == Type.SYMBOL) {
                depth = Math.max(0, depth - 1);
            }
            if (";".equals(token.text()) && token.type() == Type.SYMBOL && depth == 0) {
                statements.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        statements.add(current);
        return statements;
    }

    private static String word(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size() || tokens.get(index).type() != Type.WORD) {
            return "";
        }
        return tokens.get(index).upper();
    }

    private static String identifier(Token token) {
        return switch (token.type()) {
            case QUOTED_IDENTIFIER -> token.value();
            case WORD -> token.lower();
            default -> token.text();
        };
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
