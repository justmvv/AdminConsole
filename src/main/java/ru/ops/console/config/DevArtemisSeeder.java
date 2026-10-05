package ru.ops.console.config;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * dev profile only: fills the demo Artemis queues (commands, events, DLQ) if they are empty.
 * Publishes with a separate account (the demo broker's admin by default) — the console has no such permissions.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "console.dev.seed-artemis", havingValue = "true")
public class DevArtemisSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevArtemisSeeder.class);
    private static final String COMMANDS = "orch.payment.commands";
    private static final String EVENTS = "orch.payment.events";
    private static final String[] STATUSES = {"CREATED", "VALIDATED", "SENT_TO_CORE", "COMPLETED", "FAILED"};

    private final ConsoleProperties props;
    private final String user;
    private final String password;

    public DevArtemisSeeder(ConsoleProperties props,
                            @Value("${console.dev.artemis-seed-user:admin}") String user,
                            @Value("${console.dev.artemis-seed-password:admin}") String password) {
        this.props = props;
        this.user = user;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!props.getFeatures().isArtemis()) return;
        Thread.ofVirtual().name("dev-artemis-seeder").start(this::doSeed);
    }

    private void doSeed() {
        try (ServerLocator locator = ActiveMQClient.createServerLocator(props.getArtemis().getUrl());
             ClientSessionFactory sf = locator.createSessionFactory();
             ClientSession s = sf.createSession(user, password, false, true, true, false, 0)) {
            s.start();
            try (ClientConsumer probe = s.createConsumer(SimpleString.of(COMMANDS), null, true)) {
                if (probe.receiveImmediate() != null) {
                    log.info("Демо-очереди Artemis уже наполнены, пропускаю");
                    return;
                }
            }
            try (ClientProducer commands = s.createProducer(COMMANDS);
                 ClientProducer events = s.createProducer(EVENTS);
                 ClientProducer dlq = s.createProducer("DLQ")) {
                for (int i = 1; i <= 60; i++) {
                    String paymentId = "PAY-" + (200000 + i);
                    String amount = String.format(Locale.ROOT, "%.2f", 250 + i * 13.7);
                    commands.send(text(s, """
                            {"commandId":"%s","paymentId":"%s","type":"EXECUTE_PAYMENT","amount":%s,"currency":"RUB"}"""
                            .formatted(UUID.randomUUID(), paymentId, amount), paymentId, "gateway"));
                    events.send(text(s, """
                            {"paymentId":"%s","status":"%s","ts":"%s"}"""
                            .formatted(paymentId, STATUSES[i % STATUSES.length], Instant.now()), paymentId, "orchestrator"));
                }
                for (int i = 1; i <= 5; i++) {
                    ClientMessage m = text(s, """
                            {"commandId":"%s","paymentId":"PAY-29999%d","type":"EXECUTE_PAYMENT","amount":"не число"}"""
                            .formatted(UUID.randomUUID(), i), "PAY-29999" + i, "gateway");
                    m.putStringProperty("_AMQ_ORIG_ADDRESS", COMMANDS);
                    m.putStringProperty("_AMQ_ORIG_QUEUE", COMMANDS);
                    m.putStringProperty("errorReason", "NumberFormatException: amount");
                    dlq.send(m);
                }
                ClientMessage bin = s.createMessage(Message.BYTES_TYPE, true);
                bin.getBodyBuffer().writeBytes(new byte[]{0x50, 0x4b, 0x03, 0x04, 0x14, 0x00});
                bin.putStringProperty("_AMQ_ORIG_ADDRESS", COMMANDS);
                bin.putStringProperty("errorReason", "Unsupported body type");
                dlq.send(bin);
            }
            log.info("Демо-очереди Artemis наполнены");
        } catch (Exception e) {
            log.warn("Dev seed: Artemis недоступен ({}), демо-сообщения не созданы", e.getMessage());
        }
    }

    private static ClientMessage text(ClientSession s, String body, String paymentId, String source) {
        ClientMessage m = s.createMessage(Message.TEXT_TYPE, true);
        m.getBodyBuffer().writeNullableSimpleString(SimpleString.of(body));
        m.putStringProperty("paymentId", paymentId);
        m.putStringProperty("traceId", UUID.randomUUID().toString());
        m.putStringProperty("source", source);
        return m;
    }
}
