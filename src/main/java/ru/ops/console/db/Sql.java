package ru.ops.console.db;

/**
 * Quoting of PostgreSQL identifiers and literals.
 * Values are always passed as parameters; {@link #literal} is used
 * only for preview text shown to the user.
 */
public final class Sql {

    private Sql() {
    }

    /** Same as quote_ident: always quoted. */
    public static String ident(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Недопустимый идентификатор");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Same as quote_literal (for previews). */
    public static String literal(String value) {
        if (value == null) return "NULL";
        if (value.indexOf('\\') >= 0) {
            return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** Escapes a LIKE pattern (special characters % _ \). */
    public static String likeEscape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
