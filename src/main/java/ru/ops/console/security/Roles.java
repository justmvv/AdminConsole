package ru.ops.console.security;

/**
 * Console roles. The hierarchy is expanded at login:
 * ADMIN ⊃ OPERATOR ⊃ VIEWER.
 * <ul>
 *   <li>VIEWER — browse tables, Kafka and Artemis;</li>
 *   <li>OPERATOR — plus inserting/updating rows, publishing to Kafka and Artemis, CSV export;</li>
 *   <li>ADMIN — plus the audit log.</li>
 * </ul>
 */
public final class Roles {
    public static final String VIEWER = "VIEWER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";

    private Roles() {
    }
}
