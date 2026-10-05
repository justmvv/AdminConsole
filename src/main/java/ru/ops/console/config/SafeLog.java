package ru.ops.console.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.dao.DataAccessException;
import ru.ops.console.db.DbException;

import java.sql.SQLException;

/**
 * Describes an exception for the application log without leaking data from the target systems.
 * <p>
 * PostgreSQL error texts may contain row values ("Failing row contains (...)", "Key (id)=(42) already
 * exists", "invalid input syntax for type numeric: ..."), and JSON parser errors quote the input. For such
 * exceptions only the type and the SQLSTATE are logged; the full text is shown to the user and, where
 * relevant, kept in the audit log, which is a protected store.
 */
public final class SafeLog {

    private SafeLog() {
    }

    public static String describe(Throwable e) {
        if (e == null) return "null";
        String sqlState = null;
        boolean sensitive = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DbException db && db.sqlState() != null) sqlState = db.sqlState();
            if (t instanceof SQLException sql && sqlState == null) sqlState = sql.getSQLState();
            if (t instanceof DbException || t instanceof SQLException || t instanceof DataAccessException
                    || t instanceof JsonProcessingException) {
                sensitive = true;
            }
            if (t.getCause() == t) break;
        }
        if (!sensitive) return e.toString();
        return e.getClass().getSimpleName() + (sqlState == null ? "" : " [SQLSTATE " + sqlState + "]")
                + " (details hidden: may contain data)";
    }
}
