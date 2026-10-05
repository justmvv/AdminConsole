package ru.ops.console.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.stereotype.Component;
import ru.ops.console.config.ConsoleProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka client factory. Admin and Producer are long-lived (thread-safe);
 * a Consumer is created per browse and has no group.id — reading
 * never affects the offsets of working consumer groups.
 */
@Component
public class KafkaClients {

    private final ConsoleProperties.Kafka props;
    private final ConsoleProperties.Features features;
    private volatile Admin admin;
    private volatile KafkaProducer<byte[], byte[]> producer;

    public KafkaClients(ConsoleProperties props) {
        this.props = props.getKafka();
        this.features = props.getFeatures();
    }

    public ConsoleProperties.Kafka props() {
        return props;
    }

    private Map<String, Object> base(String suffix) {
        Map<String, Object> m = new HashMap<>();
        m.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        m.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs());
        m.put(AdminClientConfig.CLIENT_ID_CONFIG, props.getClientIdPrefix() + "-" + suffix);
        m.putAll(props.getProperties());
        return m;
    }

    public Admin admin() {
        requireEnabled();
        Admin a = admin;
        if (a == null) {
            synchronized (this) {
                if (admin == null) {
                    Map<String, Object> m = base("admin");
                    m.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs() * 2);
                    admin = Admin.create(m);
                }
                a = admin;
            }
        }
        return a;
    }

    public KafkaConsumer<byte[], byte[]> newBrowseConsumer() {
        requireEnabled();
        Map<String, Object> m = base("browse-" + UUID.randomUUID().toString().substring(0, 8));
        m.remove(ConsumerConfig.GROUP_ID_CONFIG);
        m.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        m.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        m.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        m.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
        m.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        m.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(m);
    }

    public KafkaProducer<byte[], byte[]> producer() {
        requireEnabled();
        KafkaProducer<byte[], byte[]> p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    Map<String, Object> m = base("producer");
                    m.put(ProducerConfig.ACKS_CONFIG, "all");
                    m.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
                    m.put(ProducerConfig.RETRIES_CONFIG, 3);
                    m.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Math.max(props.getRequestTimeoutMs() * 2, 30_000));
                    m.put(ProducerConfig.LINGER_MS_CONFIG, 0);
                    m.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
                    m.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
                    producer = new KafkaProducer<>(m);
                }
                p = producer;
            }
        }
        return p;
    }

    private void requireEnabled() {
        if (!features.isKafka()) {
            throw new KafkaException("Раздел Kafka выключен в настройках (console.features.kafka=false)");
        }
    }

    @PreDestroy
    void close() {
        if (admin != null) admin.close();
        if (producer != null) producer.close();
    }
}
