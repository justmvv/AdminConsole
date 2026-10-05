package ru.ops.console.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import ru.ops.console.audit.AuditService;
import ru.ops.console.audit.AuditTestSupport;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.kafka.KafkaModel.BrowseRequest;
import ru.ops.console.kafka.KafkaModel.BrowseResult;
import ru.ops.console.kafka.KafkaModel.GroupDetails;
import ru.ops.console.kafka.KafkaModel.Header;
import ru.ops.console.kafka.KafkaModel.Message;
import ru.ops.console.kafka.KafkaModel.ProduceRequest;
import ru.ops.console.kafka.KafkaModel.ProduceResult;
import ru.ops.console.kafka.KafkaModel.StartFrom;
import ru.ops.console.kafka.KafkaModel.TopicDetails;
import ru.ops.console.security.Roles;
import ru.ops.console.support.TestUsers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KafkaAdminService, KafkaBrowseService and KafkaProduceService against a real broker (KRaft).
 * Topic payments.events: 3 partitions, 300 messages — 100 each, key pay-N, JSON value.
 */
@Testcontainers
class KafkaServicesIT {

    private static final String TOPIC = "payments.events";
    private static final String OTHER = "core.commands";
    private static final int PER_PARTITION = 100;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    private static KafkaClients clients;
    private static KafkaAdminService admin;
    private static KafkaBrowseService browse;
    private static KafkaProduceService produce;
    private static Instant middle;

    @BeforeAll
    static void setUp() throws Exception {
        ConsoleProperties props = new ConsoleProperties();
        props.getKafka().setBootstrapServers(KAFKA.getBootstrapServers());
        props.getKafka().getProduce().setAllowedTopics(List.of("payments.*"));
        props.getKafka().getBrowse().setMaxScanPerPartition(1000);
        props.getAudit().setJdbcEnabled(false);

        clients = new KafkaClients(props);
        admin = new KafkaAdminService(clients);
        AuditService audit = AuditTestSupport.fileOnlyAuditService(props);
        browse = new KafkaBrowseService(clients, audit, props);
        produce = new KafkaProduceService(clients, admin, audit, props);

        clients.admin().createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1), new NewTopic(OTHER, 1, (short) 1)))
                .all().get();

        // Explicit timestamps: half of the messages "yesterday", half "today"
        long base = Instant.parse("2026-09-24T00:00:00Z").toEpochMilli();
        middle = Instant.ofEpochMilli(base + PER_PARTITION / 2 * 60_000L);
        for (int p = 0; p < 3; p++) {
            for (int i = 0; i < PER_PARTITION; i++) {
                int n = p * PER_PARTITION + i;
                String value = "{\"paymentId\":\"pay-" + n + "\",\"status\":\"" + (n % 10 == 0 ? "FAILED" : "COMPLETED") + "\"}";
                var rec = new ProducerRecord<>(TOPIC, p, base + i * 60_000L,
                        ("pay-" + n).getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
                rec.headers().add("traceId", ("t-" + n).getBytes(StandardCharsets.UTF_8));
                clients.producer().send(rec);
            }
        }
        clients.producer().flush();
    }

    @AfterAll
    static void tearDown() {
        clients.close();
    }

    @BeforeEach
    void login() {
        TestUsers.loginAs("operator", Roles.OPERATOR, Roles.VIEWER);
    }

    @AfterEach
    void logout() {
        TestUsers.logout();
    }

    private static BrowseResult browse(StartFrom from, Integer partition, Long offset, Instant ts, int limit,
                                       String key, String value, String header) {
        return browse.browse("operator", new BrowseRequest(TOPIC, partition, from, offset, ts, limit, key, value, header));
    }

    // ---------------------------------------------------------------- admin

    @Test
    void topicsAndDetails() {
        assertThat(admin.cluster().brokers()).isEqualTo(1);
        assertThat(admin.topicNames()).contains(TOPIC, OTHER).doesNotContain("__consumer_offsets");

        TopicDetails d = admin.topic(TOPIC);
        assertThat(d.partitions()).hasSize(3);
        assertThat(d.partitions()).allSatisfy(p -> {
            assertThat(p.beginOffset()).isZero();
            assertThat(p.endOffset()).isGreaterThanOrEqualTo(PER_PARTITION);
            assertThat(p.underReplicated()).isFalse();
        });
        assertThat(d.configs()).extracting(KafkaModel.ConfigEntryInfo::name).contains("retention.ms");
        assertThatThrownBy(() -> admin.topic("no.such.topic")).isInstanceOf(KafkaException.class);
    }

    @Test
    void consumerGroupLag() {
        Map<String, Object> cfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "orchestrator-notifier",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(cfg)) {
            c.commitSync(Map.of(
                    new TopicPartition(TOPIC, 0), new OffsetAndMetadata(PER_PARTITION - 10),
                    new TopicPartition(TOPIC, 1), new OffsetAndMetadata(0)));
        }
        GroupDetails g = admin.group("orchestrator-notifier");
        assertThat(g.offsets()).hasSize(2);
        assertThat(g.offsets().get(0).lag()).isGreaterThanOrEqualTo(10L);
        assertThat(g.offsets().get(1).lag()).isGreaterThanOrEqualTo(PER_PARTITION);
        assertThat(admin.groups()).anySatisfy(gi -> {
            assertThat(gi.groupId()).isEqualTo("orchestrator-notifier");
            assertThat(gi.totalLag()).isEqualTo(g.info().totalLag());
        });
    }

    // --------------------------------------------------------------- browse

    @Test
    void latestReturnsNewestAcrossPartitions() {
        BrowseResult r = browse(StartFrom.LATEST, null, null, null, 6, null, null, null);
        assertThat(r.messages()).hasSize(6);
        // newest first; the last messages of every partition have timestamp base + 99 min
        assertThat(r.messages()).isSortedAccordingTo((a, b) -> b.timestamp().compareTo(a.timestamp()));
        assertThat(r.messages()).extracting(Message::partition).containsOnly(0, 1, 2);
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    void earliestFromOnePartition() {
        BrowseResult r = browse(StartFrom.EARLIEST, 1, null, null, 5, null, null, null);
        assertThat(r.messages()).extracting(Message::offset).containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(r.messages()).extracting(m -> MessageFormat.text(m.key()))
                .containsExactly("pay-100", "pay-101", "pay-102", "pay-103", "pay-104");
    }

    @Test
    void fromOffset() {
        BrowseResult r = browse(StartFrom.OFFSET, 2, 97L, null, 50, null, null, null);
        assertThat(r.messages()).extracting(Message::offset).startsWith(97L, 98L, 99L);
        // an out-of-range offset is clamped to the end, no error
        assertThat(browse(StartFrom.OFFSET, 0, 1_000_000L, null, 10, null, null, null).messages())
                .allSatisfy(m -> assertThat(m.offset()).isGreaterThanOrEqualTo(PER_PARTITION));
    }

    @Test
    void fromTimestamp() {
        BrowseResult r = browse(StartFrom.TIMESTAMP, 0, null, middle, 1000, null, null, "traceId=t-");
        assertThat(r.messages()).hasSize(PER_PARTITION / 2);
        assertThat(r.messages()).allSatisfy(m -> assertThat(m.timestamp()).isAfterOrEqualTo(middle));
    }

    @Test
    void filtersByKeyValueAndHeader() {
        BrowseResult failed = browse(StartFrom.EARLIEST, null, null, null, 1000, null, "\"failed\"", null);
        assertThat(failed.messages()).hasSize(30)
                .allSatisfy(m -> assertThat(MessageFormat.text(m.value())).contains("FAILED"));

        BrowseResult byKey = browse(StartFrom.LATEST, null, null, null, 10, "PAY-205", null, null);
        assertThat(byKey.messages()).singleElement()
                .satisfies(m -> assertThat(m.partition()).isEqualTo(2));

        // t-17 and t-170..t-179; limit 10 keeps the 10 newest (partition 1, 70..79 min)
        BrowseResult byHeader = browse(StartFrom.LATEST, null, null, null, 10, null, null, "traceId=t-17");
        assertThat(byHeader.messages()).extracting(m -> MessageFormat.text(m.key()))
                .containsExactly("pay-179", "pay-178", "pay-177", "pay-176", "pay-175", "pay-174",
                        "pay-173", "pay-172", "pay-171", "pay-170");
    }

    @Test
    void browsingDoesNotCreateConsumerGroups() {
        browse(StartFrom.EARLIEST, null, null, null, 10, null, null, null);
        assertThat(admin.groups()).noneSatisfy(g -> assertThat(g.groupId()).startsWith("admin-console"));
    }

    @Test
    void unknownTopicOrPartition() {
        assertThatThrownBy(() -> browse.browse("operator",
                new BrowseRequest(TOPIC, 7, StartFrom.LATEST, null, null, 10, null, null, null)))
                .isInstanceOf(KafkaException.class).hasMessageContaining("Партиция 7");
    }

    // -------------------------------------------------------------- produce

    @Test
    void produceAddsUserHeaderAndIsReadable() {
        ProduceResult res = produce.send(new ProduceRequest(TOPIC, 1, "manual-1", "{\"resend\":true}",
                List.of(new Header("traceId", "manual".getBytes(StandardCharsets.UTF_8))), "INC-7"));
        assertThat(res.partition()).isEqualTo(1);

        BrowseResult r = browse(StartFrom.OFFSET, 1, res.offset(), null, 1, null, null, null);
        Message m = r.messages().get(0);
        assertThat(MessageFormat.text(m.key())).isEqualTo("manual-1");
        assertThat(m.headers()).extracting(h -> h.key() + "=" + MessageFormat.text(h.value()))
                .containsExactly("traceId=manual", "x-admin-console-user=operator");
    }

    @Test
    void produceGuards() {
        assertThat(produce.allowedTopics()).contains(TOPIC).doesNotContain(OTHER);

        assertThatThrownBy(() -> produce.send(new ProduceRequest(OTHER, null, null, "x", List.of(), "INC")))
                .isInstanceOf(KafkaException.class).hasMessageContaining("запрещена");
        assertThatThrownBy(() -> produce.send(new ProduceRequest(TOPIC, null, null, "x", List.of(), " ")))
                .isInstanceOf(KafkaException.class).hasMessageContaining("обоснование");

        TestUsers.loginAs("viewer", Roles.VIEWER);
        assertThatThrownBy(() -> produce.send(new ProduceRequest(TOPIC, null, null, "x", List.of(), "INC")))
                .isInstanceOf(AccessDeniedException.class);
    }
}
