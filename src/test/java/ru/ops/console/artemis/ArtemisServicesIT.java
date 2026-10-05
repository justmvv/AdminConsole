package ru.ops.console.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import ru.ops.console.artemis.ArtemisModel.BrowseRequest;
import ru.ops.console.artemis.ArtemisModel.BrowseResult;
import ru.ops.console.artemis.ArtemisModel.ProduceRequest;
import ru.ops.console.artemis.ArtemisModel.QueueInfo;
import ru.ops.console.artemis.ArtemisModel.QueueList;
import ru.ops.console.artemis.ArtemisModel.StartFrom;
import ru.ops.console.audit.AuditTestSupport;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.security.Roles;
import ru.ops.console.support.TestUsers;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Artemis services against a real broker with the permissions from docker/artemis/etc-override:
 * console-ro — browse only, console — browse + management, console-send — send.
 */
@Testcontainers
class ArtemisServicesIT {

    private static final String COMMANDS = "orch.payment.commands";
    private static final int SEEDED = 30;

    @Container
    static final GenericContainer<?> BROKER = new GenericContainer<>("apache/activemq-artemis:2.40.0-alpine")
            .withEnv("ARTEMIS_USER", "admin")
            .withEnv("ARTEMIS_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/artemis/etc-override"),
                    "/var/lib/artemis-instance/etc-override")
            .withExposedPorts(61616)
            .waitingFor(Wait.forLogMessage(".*AMQ221007.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    @BeforeAll
    static void seed() throws Exception {
        MINIMAL = services(props("console-ro", List.of(COMMANDS, "DLQ")));
        try (ServerLocator locator = ActiveMQClient.createServerLocator(url());
             ClientSessionFactory sf = locator.createSessionFactory();
             ClientSession s = sf.createSession("admin", "admin", false, true, true, false, 0);
             ClientProducer p = s.createProducer(COMMANDS)) {
            for (int i = 0; i < SEEDED; i++) {
                ClientMessage m = s.createMessage(Message.TEXT_TYPE, true);
                String status = i % 10 == 0 ? "FAILED" : "COMPLETED";
                m.getBodyBuffer().writeNullableSimpleString(
                        SimpleString.of("{\"paymentId\":\"pay-" + i + "\",\"status\":\"" + status + "\"}"));
                m.putStringProperty("traceId", "t-" + i);
                m.putIntProperty("attempt", i % 3);
                p.send(m);
            }
            // messages with other body types — into DLQ
            ClientProducer dlq = s.createProducer("DLQ");
            ClientMessage bytes = s.createMessage(Message.BYTES_TYPE, true);
            bytes.getBodyBuffer().writeBytes(new byte[]{1, 2, 3, (byte) 0xff});
            bytes.putStringProperty("_AMQ_ORIG_ADDRESS", COMMANDS);
            dlq.send(bytes);
            ClientMessage object = s.createMessage(Message.OBJECT_TYPE, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(new java.util.ArrayList<>(List.of("x")));
            }
            object.getBodyBuffer().writeInt(bos.size());
            object.getBodyBuffer().writeBytes(bos.toByteArray());
            dlq.send(object);
        }
    }

    private static String url() {
        return "tcp://" + BROKER.getHost() + ":" + BROKER.getMappedPort(61616);
    }

    private static ConsoleProperties props(String user, List<String> queues) {
        ConsoleProperties p = new ConsoleProperties();
        p.getFeatures().setArtemis(true);
        p.getArtemis().setUrl(url());
        p.getArtemis().setUser(user);
        p.getArtemis().setPassword(user);
        p.getArtemis().setQueues(queues);
        p.getArtemis().getBrowse().setMaxScan(1000);
        p.getArtemis().getProduce().setAllowedAddresses(List.of("orch.payment.*"));
        p.getArtemis().getProduce().setUser("console-send");
        p.getArtemis().getProduce().setPassword("console-send");
        p.getAudit().setJdbcEnabled(false);
        return p;
    }

    private record Services(ArtemisQueueService queues, ArtemisBrowseService browse, ArtemisProduceService produce) {
    }

    private static Services services(ConsoleProperties p) {
        ArtemisClients clients = new ArtemisClients(p);
        ArtemisQueueService queues = new ArtemisQueueService(clients, p);
        var audit = AuditTestSupport.fileOnlyAuditService(p);
        return new Services(queues, new ArtemisBrowseService(clients, queues, audit, p),
                new ArtemisProduceService(clients, queues, audit, p));
    }

    /** Minimal permissions: browse only. */
    private static Services MINIMAL;

    private static BrowseResult browse(Services s, String queue, StartFrom from, int limit,
                                       String body, String property, String id) {
        return s.browse().browse("operator", new BrowseRequest(queue, from, limit, body, property, id));
    }

    @BeforeEach
    void login() {
        TestUsers.loginAs("operator", Roles.OPERATOR, Roles.VIEWER);
    }

    @AfterEach
    void logout() {
        TestUsers.logout();
    }

    // ----------------------------------------------------- minimal permissions

    @Test
    void browseOnlyAccountSeesConfiguredQueuesWithoutManagement() {
        QueueList list = MINIMAL.queues().queues();
        assertThat(list.queues()).extracting(QueueInfo::name).containsExactly("DLQ", COMMANDS);
        assertThat(list.queues()).allSatisfy(q -> assertThat(q.messageCount()).isNull());
        assertThat(list.managementNote()).contains("Нет прав management");
    }

    @Test
    void browseOnlyAccountReadsMessagesAndQueueStaysIntact() {
        BrowseResult first = browse(MINIMAL, COMMANDS, StartFrom.FIRST, 5, null, null, null);
        assertThat(first.messages()).hasSize(5);
        assertThat(first.truncated()).isTrue();
        assertThat(new String(first.messages().get(0).body())).contains("pay-0");
        assertThat(first.messages().get(0).properties()).containsEntry("traceId", "t-0").containsEntry("attempt", "0");
        assertThat(first.messages().get(0).bodyType()).isEqualTo("TEXT");

        // browsing again sees the same messages — nothing was taken from the queue
        BrowseResult all = browse(MINIMAL, COMMANDS, StartFrom.FIRST, 500, null, null, null);
        assertThat(all.scanned()).isGreaterThanOrEqualTo(SEEDED);
        assertThat(browse(MINIMAL, COMMANDS, StartFrom.FIRST, 500, null, null, null).scanned()).isEqualTo(all.scanned());
    }

    @Test
    void lastNAndFilters() {
        BrowseResult last = browse(MINIMAL, COMMANDS, StartFrom.LAST, 3, "pay-", null, null);
        assertThat(last.messages()).extracting(m -> new String(m.body()))
                .containsExactly("{\"paymentId\":\"pay-29\",\"status\":\"COMPLETED\"}",
                        "{\"paymentId\":\"pay-28\",\"status\":\"COMPLETED\"}",
                        "{\"paymentId\":\"pay-27\",\"status\":\"COMPLETED\"}");

        assertThat(browse(MINIMAL, COMMANDS, StartFrom.FIRST, 100, "\"failed\"", null, null).messages()).hasSize(3);
        assertThat(browse(MINIMAL, COMMANDS, StartFrom.FIRST, 100, null, "traceid=t-1", null).messages())
                .hasSize(11);   // t-1, t-10..t-19
    }

    @Test
    void nonTextBodiesAreShownSafely() {
        List<ArtemisModel.Message> dlq = browse(MINIMAL, "DLQ", StartFrom.FIRST, 10, null, null, null).messages();
        assertThat(dlq).hasSize(2);
        assertThat(dlq.get(0).bodyType()).isEqualTo("BYTES");
        assertThat(dlq.get(0).body()).containsExactly(1, 2, 3, 0xff);
        assertThat(dlq.get(0).properties()).containsEntry("_AMQ_ORIG_ADDRESS", COMMANDS);
        assertThat(dlq.get(1).bodyType()).isEqualTo("OBJECT");
        assertThat(dlq.get(1).body()).isNull();
        assertThat(dlq.get(1).bodyNote()).contains("не десериализует");
    }

    @Test
    void queuesOutsideConfigCannotBeBrowsed() {
        assertThatThrownBy(() -> browse(MINIMAL, "ExpiryQueue", StartFrom.FIRST, 10, null, null, null))
                .isInstanceOf(ArtemisException.class).hasMessageContaining("недоступна");
        // listed in the config, but the account has no browse permission on this queue
        Services s = services(props("console-ro", List.of("ExpiryQueue")));
        assertThatThrownBy(() -> browse(s, "ExpiryQueue", StartFrom.FIRST, 10, null, null, null))
                .isInstanceOfSatisfying(ArtemisException.class, e -> {
                    assertThat(e.securityDenied()).isTrue();
                    assertThat(e.getMessage()).contains("нет прав");
                });
    }

    // -------------------------------------------------------------- management

    @Test
    void managementAccountDiscoversQueuesWithCounts() {
        Services s = services(props("console", List.of("orch.*", "DLQ")));
        QueueList list = s.queues().queues();
        assertThat(list.managementNote()).isNull();
        assertThat(list.queues()).extracting(QueueInfo::name)
                .containsExactly("DLQ", COMMANDS, "orch.payment.events.archive", "orch.payment.events.notifier")
                .noneMatch(n -> n.startsWith("admin-console.reply"));
        QueueInfo commands = list.queues().get(1);
        assertThat(commands.messageCount()).isGreaterThanOrEqualTo((long) SEEDED);
        assertThat(commands.routingType()).isEqualTo("ANYCAST");
        assertThat(list.queues().get(2).address()).isEqualTo("orch.payment.events");
        assertThat(list.queues().get(0).fromConfig()).isTrue();
        assertThat(list.queues().get(2).fromConfig()).isFalse();
    }

    // -------------------------------------------------------------- publishing

    @Test
    void produceWithSeparateSendAccount() {
        var result = MINIMAL.produce().send(new ProduceRequest(COMMANDS, null, true, "{\"resend\":true}",
                Map.of("traceId", "manual-1"), "INC-ART-1"));
        assertThat(result.messageId()).startsWith("ID:");

        ArtemisModel.Message m = browse(MINIMAL, COMMANDS, StartFrom.LAST, 1, null, "traceid=manual-1", null)
                .messages().get(0);
        assertThat(new String(m.body())).isEqualTo("{\"resend\":true}");
        assertThat(m.userId()).isEqualTo(result.messageId());
        assertThat(m.properties()).containsEntry(ArtemisProduceService.USER_PROPERTY, "operator");
        assertThat(m.durable()).isTrue();
    }

    @Test
    void sentMessageIsJmsTextMessage() throws Exception {
        MINIMAL.produce().send(new ProduceRequest(COMMANDS, "ANYCAST", false, "проверка JMS", Map.of(), "INC"));
        // read as admin like a regular consumer: TEXT type, same text
        try (ServerLocator locator = ActiveMQClient.createServerLocator(url());
             ClientSessionFactory sf = locator.createSessionFactory();
             ClientSession s = sf.createSession("admin", "admin", false, true, true, false, 0);
             ClientConsumer c = s.createConsumer(SimpleString.of(COMMANDS), null, true)) {
            s.start();
            ClientMessage found = null;
            for (ClientMessage m = c.receiveImmediate(); m != null; m = c.receiveImmediate()) {
                if (m.getType() == Message.TEXT_TYPE
                        && "проверка JMS".equals(String.valueOf(m.getReadOnlyBodyBuffer().readNullableSimpleString()))) {
                    found = m;
                }
            }
            assertThat(found).isNotNull();
            assertThat(found.getStringProperty(ArtemisProduceService.USER_PROPERTY)).isEqualTo("operator");
            assertThat(found.isDurable()).isFalse();
        }
    }

    @Test
    void produceGuards() {
        assertThat(MINIMAL.produce().allowedAddresses()).containsExactly(COMMANDS);
        assertThatThrownBy(() -> MINIMAL.produce().send(new ProduceRequest("DLQ", null, true, "x", Map.of(), "INC")))
                .isInstanceOf(ArtemisException.class).hasMessageContaining("запрещена настройками");
        assertThatThrownBy(() -> MINIMAL.produce().send(new ProduceRequest(COMMANDS, null, true, "x", Map.of(), " ")))
                .isInstanceOf(ArtemisException.class).hasMessageContaining("обоснование");

        // the console allows the address, but the publishing account has no send permission on it
        assertThatThrownBy(() -> MINIMAL.produce().send(
                new ProduceRequest("orch.payment.events", null, true, "x", Map.of(), "INC")))
                .isInstanceOfSatisfying(ArtemisException.class, e -> assertThat(e.securityDenied()).isTrue());

        TestUsers.loginAs("viewer", Roles.VIEWER);
        assertThatThrownBy(() -> MINIMAL.produce().send(new ProduceRequest(COMMANDS, null, true, "x", Map.of(), "INC")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disabledFeatureRefusesToConnect() {
        ConsoleProperties p = props("console-ro", List.of(COMMANDS));
        p.getFeatures().setArtemis(false);
        assertThatThrownBy(() -> services(p).browse().browse("operator",
                new BrowseRequest(COMMANDS, StartFrom.FIRST, 1, null, null, null)))
                .isInstanceOf(ArtemisException.class).hasMessageContaining("выключен");
    }
}
