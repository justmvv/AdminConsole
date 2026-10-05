package ru.ops.console.kafka;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Kafka error with an understandable message. */
public class KafkaException extends RuntimeException {

    public KafkaException(String message) {
        super(message);
    }

    public KafkaException(String message, Throwable cause) {
        super(message, cause);
    }

    public static KafkaException wrap(String action, Throwable e) {
        Throwable cause = e;
        if (e instanceof ExecutionException && e.getCause() != null) cause = e.getCause();
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        String msg = cause instanceof TimeoutException || cause instanceof org.apache.kafka.common.errors.TimeoutException
                ? "таймаут — брокеры недоступны или перегружены"
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new KafkaException(action + ": " + msg, cause);
    }
}
