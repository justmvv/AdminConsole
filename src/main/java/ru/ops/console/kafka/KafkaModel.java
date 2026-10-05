package ru.ops.console.kafka;

import java.time.Instant;
import java.util.List;

public final class KafkaModel {

    private KafkaModel() {
    }

    public record ClusterInfo(String clusterId, int brokers, String controller, List<String> nodes) {
    }

    public record TopicInfo(String name, int partitions, int replicationFactor, boolean internal, long messages) {
    }

    public record PartitionInfo(int partition, String leader, String replicas, String isr,
                                long beginOffset, long endOffset, boolean underReplicated) {
        public long messages() {
            return Math.max(0, endOffset - beginOffset);
        }
    }

    public record ConfigEntryInfo(String name, String value, String source, boolean isDefault, boolean sensitive) {
    }

    public record TopicDetails(String name, boolean internal, List<PartitionInfo> partitions,
                               List<ConfigEntryInfo> configs) {
    }

    // --------------------------------------------------------------- messages

    public enum StartFrom {
        LATEST("Последние N"), EARLIEST("С начала"), OFFSET("С офсета"), TIMESTAMP("С момента времени");

        private final String label;

        StartFrom(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record BrowseRequest(
            String topic,
            Integer partition,     // null — all partitions
            StartFrom startFrom,
            Long offset,           // for OFFSET
            Instant timestamp,     // for TIMESTAMP
            int limit,
            String keyFilter,      // substring, case-insensitive
            String valueFilter,
            String headerFilter) {
    }

    public record Header(String key, byte[] value) {
    }

    public record Message(String topic, int partition, long offset, Instant timestamp, String timestampType,
                          byte[] key, byte[] value, List<Header> headers) {
    }

    public record BrowseResult(List<Message> messages, long scanned, boolean truncated, boolean timedOut,
                               long elapsedMs) {
    }

    // ---------------------------------------------------- consumer groups

    public record GroupInfo(String groupId, String state, int members, long totalLag, int partitions,
                            List<String> topics, String coordinator) {
    }

    public record GroupMember(String memberId, String clientId, String host, String assignment) {
    }

    public record GroupOffset(String topic, int partition, Long committed, long endOffset, Long lag,
                              String memberClientId, String metadata) {
    }

    public record GroupDetails(GroupInfo info, String assignor, List<GroupMember> members,
                               List<GroupOffset> offsets) {
    }

    // ---------------------------------------------------------------- publishing

    public record ProduceRequest(String topic, Integer partition, String key, String value,
                                 List<Header> headers, String reason) {
    }

    public record ProduceResult(String topic, int partition, long offset, Instant timestamp) {
    }
}
