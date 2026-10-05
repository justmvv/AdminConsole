package ru.ops.console.artemis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ArtemisModel {

    private ArtemisModel() {
    }

    /**
     * @param messageCount  null — unknown (no management permissions)
     * @param fromConfig    the queue is listed in console.artemis.queues
     */
    public record QueueInfo(String name, String address, String routingType, Boolean durable,
                            Long messageCount, Integer consumerCount, boolean fromConfig) {
    }

    /** @param managementNote why management data is missing (null — everything was retrieved) */
    public record QueueList(List<QueueInfo> queues, String managementNote) {
    }

    public enum StartFrom {
        FIRST("С головы очереди"), LAST("Последние N");

        private final String label;

        StartFrom(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record BrowseRequest(String queue, StartFrom startFrom, int limit,
                                String bodyFilter, String propertyFilter, String idFilter) {
    }

    /**
     * @param messageId  internal message id in the broker
     * @param userId     JMSMessageID (if set by the sender)
     * @param bodyType   body type: TEXT, BYTES, MAP, OBJECT ...
     * @param body       body (text as UTF-8); null — not loaded, see bodyNote
     */
    public record Message(long messageId, String userId, Instant timestamp, String address, int priority,
                          boolean durable, Instant expiration, String bodyType, long bodySize, byte[] body,
                          String bodyNote, Map<String, String> properties) {
    }

    public record BrowseResult(List<Message> messages, long scanned, boolean truncated, boolean timedOut,
                               long elapsedMs) {
    }

    /** @param routingType null — per address settings; ANYCAST / MULTICAST */
    public record ProduceRequest(String address, String routingType, boolean durable, String body,
                                 Map<String, String> properties, String reason) {
    }

    public record ProduceResult(String address, String messageId, Instant timestamp) {
    }
}
