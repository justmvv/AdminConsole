package ru.ops.console.audit;

import java.time.Instant;

/**
 * Audit log record.
 *
 * @param action  action type (see {@link AuditAction})
 * @param target  object: schema.table, topic, group ...
 * @param reason  justification entered by the user (ticket/incident number)
 * @param details details as JSON
 */
public record AuditEvent(
        Long id,
        Instant ts,
        String username,
        AuditAction action,
        String target,
        String reason,
        boolean success,
        String details) {
}
