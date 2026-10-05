package ru.ops.console.db;

import java.sql.SQLException;

/** Database error with a message an operator can understand (SQLSTATE + server text). */
public class DbException extends RuntimeException {

    private final String sqlState;

    public DbException(SQLException e) {
        super(format(e), e);
        this.sqlState = e.getSQLState();
    }

    public DbException(String message) {
        super(message);
        this.sqlState = null;
    }

    public String sqlState() {
        return sqlState;
    }

    private static String format(SQLException e) {
        String msg = e.getMessage();
        if ("55P03".equals(e.getSQLState())) {
            msg = "Строки сейчас заблокированы другим процессом (например, оркестратор их обрабатывает). "
                    + "Повторите чуть позже. " + msg;
        } else if ("57014".equals(e.getSQLState())) {
            msg = "Превышен таймаут запроса. Уточните фильтр (лучше по индексированной колонке). " + msg;
        }
        return (e.getSQLState() != null ? "[" + e.getSQLState() + "] " : "") + msg;
    }
}
