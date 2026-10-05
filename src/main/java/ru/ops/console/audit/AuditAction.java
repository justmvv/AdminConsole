package ru.ops.console.audit;

public enum AuditAction {
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    SESSION_TIMEOUT,
    DB_VIEW,
    DB_EXPORT,
    DB_INSERT,
    DB_UPDATE,
    DB_BULK_UPDATE,
    KAFKA_BROWSE,
    KAFKA_PRODUCE,
    ARTEMIS_BROWSE,
    ARTEMIS_PRODUCE
}
