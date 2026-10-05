package ru.ops.console.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.stereotype.Service;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.config.Globs;
import ru.ops.console.kafka.KafkaModel.Header;
import ru.ops.console.kafka.KafkaModel.ProduceRequest;
import ru.ops.console.kafka.KafkaModel.ProduceResult;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a message to a topic (acks=all, idempotent producer). OPERATOR role only,
 * and only to topics from the whitelist {@code console.kafka.produce.allowed-topics}.
 */
@Service
public class KafkaProduceService {

    private final KafkaClients clients;
    private final KafkaAdminService admin;
    private final AuditService audit;
    private final boolean readOnlyMode;
    private final ConsoleProperties.Produce props;

    public KafkaProduceService(KafkaClients clients, KafkaAdminService admin, AuditService audit,
                               ConsoleProperties props) {
        this.clients = clients;
        this.admin = admin;
        this.audit = audit;
        this.props = props.getKafka().getProduce();
        this.readOnlyMode = props.isReadOnly();
    }

    public boolean isEnabled() {
        return !readOnlyMode && props.isEnabled() && !props.getAllowedTopics().isEmpty();
    }

    public boolean isReasonRequired() {
        return props.isRequireReason();
    }

    public boolean isTopicAllowed(String topic) {
        return !readOnlyMode && props.isEnabled() && Globs.matchesAny(topic, props.getAllowedTopics());
    }

    public List<String> allowedTopics() {
        return admin.topicNames().stream().filter(this::isTopicAllowed).toList();
    }

    public ProduceResult send(ProduceRequest req) {
        CurrentUser.require(Roles.OPERATOR);
        String user = CurrentUser.name();
        if (req.topic() == null || !isTopicAllowed(req.topic())) {
            throw new KafkaException("Отправка в топик " + req.topic() + " запрещена настройками консоли");
        }
        if (props.isRequireReason() && (req.reason() == null || req.reason().isBlank())) {
            throw new KafkaException("Укажите обоснование (номер заявки/инцидента)");
        }

        byte[] key = req.key() == null || req.key().isEmpty() ? null : req.key().getBytes(StandardCharsets.UTF_8);
        byte[] value = req.value() == null ? null : req.value().getBytes(StandardCharsets.UTF_8);
        List<org.apache.kafka.common.header.Header> headers = new ArrayList<>();
        Map<String, String> headersForAudit = new LinkedHashMap<>();
        if (req.headers() != null) {
            for (Header h : req.headers()) {
                headers.add(new RecordHeader(h.key(), h.value()));
                headersForAudit.put(h.key(), h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8));
            }
        }
        // Service header: who sent it (visible to consumers and during investigations)
        headers.add(new RecordHeader("x-admin-console-user", user.getBytes(StandardCharsets.UTF_8)));

        ProducerRecord<byte[], byte[]> record =
                new ProducerRecord<>(req.topic(), req.partition(), null, key, value, headers);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("partition", req.partition());
        details.put("key", req.key());
        details.put("headers", headersForAudit);
        details.put("value", req.value());
        try {
            RecordMetadata md = clients.producer().send(record)
                    .get(Math.max(clients.props().getRequestTimeoutMs() * 2L, 30_000L), TimeUnit.MILLISECONDS);
            details.put("resultPartition", md.partition());
            details.put("resultOffset", md.offset());
            audit.record(user, AuditAction.KAFKA_PRODUCE, req.topic(), req.reason(), true, details);
            return new ProduceResult(md.topic(), md.partition(), md.offset(),
                    md.hasTimestamp() ? Instant.ofEpochMilli(md.timestamp()) : Instant.now());
        } catch (Exception e) {
            details.put("error", String.valueOf(e.getMessage()));
            audit.record(user, AuditAction.KAFKA_PRODUCE, req.topic(), req.reason(), false, details);
            throw KafkaException.wrap("Отправка в " + req.topic(), e);
        }
    }
}
