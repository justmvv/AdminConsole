package ru.ops.console.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.utils.UUIDGenerator;
import org.springframework.stereotype.Service;
import ru.ops.console.artemis.ArtemisModel.ProduceRequest;
import ru.ops.console.artemis.ArtemisModel.ProduceResult;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.config.Globs;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes a text message (compatible with JMS TextMessage) to an Artemis address.
 * OPERATOR only, whitelisted addresses only ({@code console.artemis.produce.allowed-addresses});
 * the publishing account needs the send permission. The broker confirms the send synchronously.
 */
@Service
public class ArtemisProduceService {

    public static final String USER_PROPERTY = "x-admin-console-user";

    private final ArtemisClients clients;
    private final ArtemisQueueService queues;
    private final AuditService audit;
    private final boolean readOnlyMode;
    private final ConsoleProperties.ArtemisProduce props;

    public ArtemisProduceService(ArtemisClients clients, ArtemisQueueService queues, AuditService audit,
                                 ConsoleProperties props) {
        this.clients = clients;
        this.queues = queues;
        this.audit = audit;
        this.props = props.getArtemis().getProduce();
        this.readOnlyMode = props.isReadOnly();
    }

    public boolean isEnabled() {
        return !readOnlyMode && props.isEnabled() && !props.getAllowedAddresses().isEmpty();
    }

    public boolean isReasonRequired() {
        return props.isRequireReason();
    }

    public boolean isAddressAllowed(String address) {
        return !readOnlyMode && props.isEnabled() && address != null && Globs.matchesAny(address, props.getAllowedAddresses());
    }

    /** Allowed addresses: addresses known to the console that match the masks + exact names from the whitelist. */
    public List<String> allowedAddresses() {
        List<String> result = new ArrayList<>();
        props.getAllowedAddresses().stream().map(String::trim).filter(a -> !a.isEmpty() && !a.contains("*"))
                .forEach(result::add);
        queues.knownAddresses().stream().filter(this::isAddressAllowed).filter(a -> !result.contains(a))
                .forEach(result::add);
        return result.stream().sorted().toList();
    }

    public ProduceResult send(ProduceRequest req) {
        CurrentUser.require(Roles.OPERATOR);
        String user = CurrentUser.name();
        if (!isAddressAllowed(req.address())) {
            throw new ArtemisException("Отправка на адрес " + req.address() + " запрещена настройками консоли");
        }
        if (props.isRequireReason() && (req.reason() == null || req.reason().isBlank())) {
            throw new ArtemisException("Укажите обоснование (номер заявки/инцидента)");
        }
        RoutingType routing = req.routingType() == null || req.routingType().isBlank()
                ? null : RoutingType.valueOf(req.routingType());

        String messageId = "ID:" + UUIDGenerator.getInstance().generateUUID();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("messageId", messageId);
        details.put("routingType", routing == null ? null : routing.name());
        details.put("durable", req.durable());
        details.put("properties", req.properties());
        details.put("body", req.body());
        try (ArtemisClients.Connection conn = clients.produceConnection()) {
            ClientSession session = conn.session();
            try (ClientProducer producer = session.createProducer(req.address())) {
                ClientMessage msg = session.createMessage(Message.TEXT_TYPE, req.durable());
                msg.getBodyBuffer().writeNullableSimpleString(req.body() == null ? null : SimpleString.of(req.body()));
                msg.setUserID(UUIDGenerator.getInstance().fromJavaUUID(
                        java.util.UUID.fromString(messageId.substring(3))));
                msg.setTimestamp(System.currentTimeMillis());
                if (routing != null) msg.setRoutingType(routing);
                if (req.properties() != null) req.properties().forEach(msg::putStringProperty);
                // Service property: who sent it (visible to consumers and during investigations)
                msg.putStringProperty(USER_PROPERTY, user);
                producer.send(msg);
            }
            audit.record(user, AuditAction.ARTEMIS_PRODUCE, req.address(), req.reason(), true, details);
            return new ProduceResult(req.address(), messageId, Instant.now());
        } catch (Exception e) {
            details.put("error", String.valueOf(e.getMessage()));
            audit.record(user, AuditAction.ARTEMIS_PRODUCE, req.address(), req.reason(), false, details);
            throw ArtemisException.wrap("Отправка на " + req.address(), e);
        }
    }
}
