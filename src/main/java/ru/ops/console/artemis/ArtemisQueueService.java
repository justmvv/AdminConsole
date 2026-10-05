package ru.ops.console.artemis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.core.client.impl.ClientMessageImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ops.console.artemis.ArtemisModel.QueueInfo;
import ru.ops.console.artemis.ArtemisModel.QueueList;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.config.Globs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Artemis queue list.
 * <ul>
 *   <li>Exact names from {@code console.artemis.queues} are always available — browsing needs only the browse permission.</li>
 *   <li>If management is permitted, the console discovers other queues (filtered by the masks from the same list)
 *       and shows message and consumer counts. Without permissions it works without them and explains why.</li>
 * </ul>
 */
@Service
public class ArtemisQueueService {

    private static final Logger log = LoggerFactory.getLogger(ArtemisQueueService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_QUEUES = 5_000;

    private final ArtemisClients clients;
    private final ConsoleProperties.Artemis props;

    public ArtemisQueueService(ArtemisClients clients, ConsoleProperties props) {
        this.clients = clients;
        this.props = props.getArtemis();
    }

    public String url() {
        return props.getUrl();
    }

    public String user() {
        return props.getUser();
    }

    /** The queue may be browsed in the console: it is in the list (exactly or by mask) or the list is empty. */
    public boolean isVisible(String queue) {
        if (queue == null || queue.isBlank() || isServiceQueue(queue)) return false;
        return props.getQueues().isEmpty() || Globs.matchesAny(queue, props.getQueues());
    }

    private boolean isServiceQueue(String name) {
        return name.startsWith("$") || name.startsWith(props.getManagement().getReplyPrefix())
                || name.startsWith(props.getManagement().getAddress());
    }

    private List<String> configuredExact() {
        return props.getQueues().stream().map(String::trim)
                .filter(q -> !q.isEmpty() && !q.contains("*")).toList();
    }

    public QueueList queues() {
        Map<String, QueueInfo> result = new LinkedHashMap<>();
        for (String q : configuredExact()) {
            result.put(q, new QueueInfo(q, null, null, null, null, null, true));
        }
        String note = null;
        if (!props.getManagement().isEnabled()) {
            note = "Management выключен (console.artemis.management.enabled=false) — показаны очереди из настроек, "
                    + "без числа сообщений.";
        } else {
            try {
                for (QueueInfo q : listViaManagement()) {
                    if (!isVisible(q.name())) continue;
                    boolean configured = result.containsKey(q.name());
                    result.put(q.name(), new QueueInfo(q.name(), q.address(), q.routingType(), q.durable(),
                            q.messageCount(), q.consumerCount(), configured));
                }
            } catch (ArtemisException e) {
                log.info("Management Artemis недоступен: {}", e.getMessage());
                note = (e.securityDenied()
                        ? "Нет прав management у учётной записи консоли"
                        : "Management недоступен")
                        + " — показаны очереди из настроек (console.artemis.queues), без числа сообщений. "
                        + "Просмотр работает с одним правом browse.";
            }
        }
        List<QueueInfo> list = new ArrayList<>(result.values());
        list.sort(Comparator.comparing(QueueInfo::name));
        return new QueueList(list, note);
    }

    /** All addresses known to the console (for the publish form). */
    public List<String> knownAddresses() {
        return queues().queues().stream()
                .map(q -> q.address() != null ? q.address() : q.name())
                .distinct().sorted().toList();
    }

    // ---------------------------------------------------------- management

    private List<QueueInfo> listViaManagement() {
        String options = "{\"field\":\"\",\"operation\":\"\",\"value\":\"\",\"sortOrder\":\"asc\",\"sortColumn\":\"name\"}";
        String json = (String) invoke(ResourceNames.BROKER, "listQueues", options, 1, MAX_QUEUES);
        List<QueueInfo> result = new ArrayList<>();
        try {
            for (JsonNode q : JSON.readTree(json).path("data")) {
                if (q.path("temporary").asBoolean(false) || q.path("internalQueue").asBoolean(false)) continue;
                result.add(new QueueInfo(
                        q.path("name").asText(),
                        q.path("address").asText(null),
                        q.path("routingType").asText(null),
                        q.has("durable") ? q.path("durable").asBoolean() : null,
                        q.has("messageCount") ? q.path("messageCount").asLong() : null,
                        q.has("consumerCount") ? q.path("consumerCount").asInt() : null,
                        false));
            }
        } catch (Exception e) {
            throw ArtemisException.wrap("Разбор ответа management", e);
        }
        return result;
    }

    /**
     * Invokes a management operation over core: request to {@code activemq.management}, reply to a temporary
     * queue {@code <reply-prefix>.<uuid>} that is deleted together with the session.
     */
    Object invoke(String resource, String operation, Object... params) {
        try (ArtemisClients.Connection conn = clients.browseConnection()) {
            ClientSession session = conn.session();
            String reply = props.getManagement().getReplyPrefix() + "." + UUID.randomUUID();
            session.createQueue(QueueConfiguration.of(reply).setAddress(reply).setRoutingType(RoutingType.ANYCAST)
                    .setDurable(false).setTemporary(true));
            try (ClientConsumer consumer = session.createConsumer(reply);
                 ClientProducer producer = session.createProducer(props.getManagement().getAddress())) {
                session.start();
                ClientMessage request = session.createMessage(false);
                ManagementHelper.putOperationInvocation(request, resource, operation, params);
                request.putStringProperty(ClientMessageImpl.REPLYTO_HEADER_NAME, reply);
                producer.send(request);
                ClientMessage response = consumer.receive(props.getRequestTimeoutMs());
                if (response == null) throw new ArtemisException("Management: нет ответа от брокера");
                response.acknowledge();
                if (!ManagementHelper.hasOperationSucceeded(response)) {
                    String error = String.valueOf(ManagementHelper.getResult(response));
                    throw error.contains("permission")
                            ? ArtemisException.denied("Management: " + error)
                            : new ArtemisException("Management: " + error);
                }
                return ManagementHelper.getResult(response, String.class);
            }
        } catch (Exception e) {
            throw ArtemisException.wrap("Management Artemis (" + operation + ")", e);
        }
    }
}
