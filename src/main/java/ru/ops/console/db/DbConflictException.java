package ru.ops.console.db;

import ru.ops.console.db.DbModel.Row;

/** The row changed (or disappeared) since the user saw it. */
public class DbConflictException extends DbException {

    private final transient Row current;

    public DbConflictException(String message, Row current) {
        super(message);
        this.current = current;
    }

    /** Current state of the row (null — the row no longer exists). */
    public Row current() {
        return current;
    }
}
