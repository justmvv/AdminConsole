package ru.ops.console.artemis;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;

/** Artemis error with a message an operator can understand. */
public class ArtemisException extends RuntimeException {

    private final boolean securityDenied;

    public ArtemisException(String message) {
        super(message);
        this.securityDenied = false;
    }

    private ArtemisException(String message, Throwable cause, boolean securityDenied) {
        super(message, cause);
        this.securityDenied = securityDenied;
    }

    /** The broker denied access (the console's account lacks the required permission). */
    public boolean securityDenied() {
        return securityDenied;
    }

    /** A permission denial reported as text (e.g. in a management reply). */
    public static ArtemisException denied(String message) {
        return new ArtemisException(message, null, true);
    }

    public static ArtemisException wrap(String action, Throwable e) {
        if (e instanceof ArtemisException ae) return ae;
        ActiveMQException amq = find(e);
        if (amq != null) {
            String hint = switch (amq.getType()) {
                case SECURITY_EXCEPTION -> "нет прав у учётной записи консоли в брокере: ";
                case QUEUE_DOES_NOT_EXIST -> "очередь не существует: ";
                case NOT_CONNECTED, CONNECTION_TIMEDOUT, DISCONNECTED -> "брокер недоступен: ";
                default -> "";
            };
            return new ArtemisException(action + " — " + hint + amq.getMessage(), e,
                    amq.getType() == ActiveMQExceptionType.SECURITY_EXCEPTION);
        }
        return new ArtemisException(action + " — " + e.getMessage(), e, false);
    }

    private static ActiveMQException find(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ActiveMQException a) return a;
        }
        return null;
    }
}
