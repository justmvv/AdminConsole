package ru.ops.console.config;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * dev profile only: creates demo orchestrator topics, fills them with messages
 * and creates consumer groups with lag, so there is something to look at in the console.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "console.dev.seed-kafka", havingValue = "true")
public class DevKafkaSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevKafkaSeeder.class);
    private static final String[] STATUSES = {"CREATED", "VALIDATED", "SENT_TO_CORE", "COMPLETED", "FAILED"};

    private final ConsoleProperties props;

    public DevKafkaSeeder(ConsoleProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!props.getFeatures().isKafka()) return;
        Thread.ofVirtual().name("dev-kafka-seeder").start(this::doSeed);
    }

    private void doSeed() {
        String bootstrap = props.getKafka().getBootstrapServers();
        Map<String, Object> base = new HashMap<>();
        base.put("bootstrap.servers", bootstrap);
        base.putAll(props.getKafka().getProperties());

        try (Admin admin = Admin.create(base)) {
            Set<String> existing = admin.listTopics().names().get(20, TimeUnit.SECONDS);
            if (existing.contains("payments.commands")) {
                log.info("Демо-топики уже есть, наполнение пропущено");
                return;
            }
            admin.createTopics(List.of(
                    new NewTopic("payments.commands", 3, (short) 1),
                    new NewTopic("payments.events", 3, (short) 1),
                    new NewTopic("payments.dlq", 1, (short) 1),
                    new NewTopic("ledger.postings", 2, (short) 1)
            )).all().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Dev seed: Kafka недоступна ({}), демо-данные не созданы", e.getMessage());
            return;
        }

        Map<String, Object> pp = new HashMap<>(base);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(pp)) {
            for (int i = 1; i <= 120; i++) {
                String paymentId = "PAY-" + (100000 + i);
                String status = STATUSES[i % STATUSES.length];
                String amount = String.format(java.util.Locale.ROOT, "%.2f", 100 + i * 37.5);
                String command = """
                        {"commandId":"%s","paymentId":"%s","type":"EXECUTE_PAYMENT","amount":%s,"currency":"RUB",\
                        "payer":{"account":"40702810%012d","bic":"044525225"},\
                        "payee":{"account":"40817810%012d","bic":"044525974"},"createdAt":"%s"}"""
                        .formatted(UUID.randomUUID(), paymentId, amount, i, i * 7, Instant.now());
                ProducerRecord<String, String> rec = new ProducerRecord<>("payments.commands", paymentId, command);
                rec.headers().add(new RecordHeader("traceId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
                rec.headers().add(new RecordHeader("source", "gateway".getBytes(StandardCharsets.UTF_8)));
                producer.send(rec);

                String event = """
                        {"paymentId":"%s","status":"%s","step":%d,"ts":"%s"}"""
                        .formatted(paymentId, status, i % 5, Instant.now());
                producer.send(new ProducerRecord<>("payments.events", paymentId, event));

                if ("FAILED".equals(status)) {
                    String dlq = """
                            {"paymentId":"%s","error":"CORE_TIMEOUT","attempts":3,"original":%s}"""
                            .formatted(paymentId, command);
                    producer.send(new ProducerRecord<>("payments.dlq", paymentId, dlq));
                }
                producer.send(new ProducerRecord<>("ledger.postings", paymentId,
                        "{\"paymentId\":\"" + paymentId + "\",\"debit\":\"40702810\",\"credit\":\"30102810\",\"amount\":"
                                + amount + "}"));
            }
            producer.flush();
        } catch (Exception e) {
            log.warn("Dev seed: не удалось отправить сообщения: {}", e.getMessage());
            return;
        }

        // Consumer groups: orchestrator-core "lags behind", notification-service has read everything
        commitGroup(base, "orchestrator-core", "payments.commands", 0.7);
        commitGroup(base, "notification-service", "payments.events", 1.0);
        commitGroup(base, "dlq-reprocessor", "payments.dlq", 0.0);
        log.info("Dev seed: демо-топики и consumer groups созданы");
    }

    private void commitGroup(Map<String, Object> base, String group, String topic, double share) {
        Map<String, Object> cp = new HashMap<>(base);
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cp)) {
            List<TopicPartition> tps = new ArrayList<>();
            consumer.partitionsFor(topic).forEach(p -> tps.add(new TopicPartition(topic, p.partition())));
            Map<TopicPartition, Long> end = consumer.endOffsets(tps);
            Map<TopicPartition, OffsetAndMetadata> commit = new HashMap<>();
            end.forEach((tp, off) -> commit.put(tp, new OffsetAndMetadata((long) Math.floor(off * share))));
            consumer.assign(tps);
            consumer.commitSync(commit);
        } catch (Exception e) {
            log.warn("Dev seed: группа {}: {}", group, e.getMessage());
        }
    }
}
