package ru.ops.console.artemis;

import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.utils.collections.TypedProperties;
import org.springframework.stereotype.Service;
import ru.ops.console.artemis.ArtemisModel.BrowseRequest;
import ru.ops.console.artemis.ArtemisModel.BrowseResult;
import ru.ops.console.artemis.ArtemisModel.StartFrom;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConcurrencyLimit;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.kafka.MessageFormat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Browses a queue without changing its state: a browse-only consumer — messages are neither consumed
 * nor acknowledged, the broker only requires the <b>browse</b> permission on the queue. Nothing is created.
 * <p>
 * The queue is read from its head; "last N" mode scans up to {@code max-scan} messages
 * and keeps the N most recent matching ones.
 */
@Service
public class ArtemisBrowseService {

    private final ArtemisClients clients;
    private final ArtemisQueueService queues;
    private final AuditService audit;
    private final ConsoleProperties.ArtemisBrowse props;
    private final ConcurrencyLimit limit;

    public ArtemisBrowseService(ArtemisClients clients, ArtemisQueueService queues, AuditService audit,
                                ConsoleProperties props) {
        this.clients = clients;
        this.queues = queues;
        this.audit = audit;
        this.props = props.getArtemis().getBrowse();
        this.limit = new ConcurrencyLimit("просмотр Artemis", props.getLimits().getMaxParallelBrowse(),
                props.getLimits().getWaitMs());
    }

    public int maxMessages() {
        return props.getMaxMessages();
    }

    public int maxScan() {
        return props.getMaxScan();
    }

    /** May take a while — call from a background thread, the user is passed explicitly. */
    public BrowseResult browse(String user, BrowseRequest req) {
        return limit.run(() -> doBrowse(user, req));
    }

    private BrowseResult doBrowse(String user, BrowseRequest req) {
        if (!queues.isVisible(req.queue())) {
            throw new ArtemisException("Очередь " + req.queue() + " недоступна в консоли (console.artemis.queues)");
        }
        long started = System.currentTimeMillis();
        long deadline = started + props.getTimeoutMs();
        int limit = Math.max(1, Math.min(req.limit(), props.getMaxMessages()));
        boolean last = req.startFrom() == StartFrom.LAST;

        Deque<ArtemisModel.Message> collected = new ArrayDeque<>();
        long scanned = 0;
        boolean truncated = false;
        boolean timedOut = false;
        try (ArtemisClients.Connection conn = clients.browseConnection()) {
            ClientSession session = conn.session();
            try (ClientConsumer consumer = session.createConsumer(SimpleString.of(req.queue()), null, true)) {
                session.start();
                ClientMessage m;
                while ((m = consumer.receiveImmediate()) != null) {
                    scanned++;
                    ArtemisModel.Message msg = toMessage(m);
                    if (matches(msg, req)) {
                        collected.addLast(msg);
                        if (last && collected.size() > limit) collected.removeFirst();
                        if (!last && collected.size() >= limit) {
                            truncated = consumer.receiveImmediate() != null;
                            break;
                        }
                    }
                    if (scanned >= props.getMaxScan()) {
                        truncated = consumer.receiveImmediate() != null;
                        break;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            audit.record(user, AuditAction.ARTEMIS_BROWSE, req.queue(), null, false,
                    Map.of("error", String.valueOf(e.getMessage())));
            throw ArtemisException.wrap("Просмотр очереди " + req.queue(), e);
        }

        List<ArtemisModel.Message> result = new ArrayList<>(collected);
        if (last) java.util.Collections.reverse(result);   // newest first
        long elapsed = System.currentTimeMillis() - started;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", req.startFrom().name());
        details.put("limit", limit);
        details.put("bodyFilter", req.bodyFilter());
        details.put("propertyFilter", req.propertyFilter());
        details.put("idFilter", req.idFilter());
        details.put("returned", result.size());
        details.put("scanned", scanned);
        audit.record(user, AuditAction.ARTEMIS_BROWSE, req.queue(), null, true, details);
        return new BrowseResult(result, scanned, truncated, timedOut, elapsed);
    }

    private ArtemisModel.Message toMessage(ClientMessage m) {
        Map<String, String> properties = new TreeMap<>();
        for (SimpleString name : m.getPropertyNames()) {
            Object v = m.getObjectProperty(name);
            properties.put(name.toString(), v instanceof byte[] b ? "0x" + MessageFormat.hex(b) : String.valueOf(v));
        }
        long bodySize = m.getBodySize();
        String type = bodyType(m.getType());
        byte[] body = null;
        String note = null;
        if (m.isLargeMessage() && bodySize > props.getMaxBodyBytes()) {
            note = "Большое сообщение (" + bodySize + " байт) — тело не загружается (лимит "
                    + props.getMaxBodyBytes() + ", console.artemis.browse.max-body-bytes)";
        } else {
            try {
                ActiveMQBuffer buf = m.getReadOnlyBodyBuffer();
                switch (m.getType()) {
                    case Message.TEXT_TYPE -> {
                        SimpleString text = buf.readNullableSimpleString();
                        body = text == null ? null : text.toString().getBytes(StandardCharsets.UTF_8);
                        if (text == null) note = "Пустое текстовое сообщение (null)";
                    }
                    case Message.MAP_TYPE -> {
                        TypedProperties map = new TypedProperties();
                        map.decode(buf.byteBuf());
                        body = String.valueOf(map).getBytes(StandardCharsets.UTF_8);
                    }
                    case Message.OBJECT_TYPE -> note = "Сериализованный Java-объект (" + bodySize
                            + " байт) — консоль не десериализует такие сообщения из соображений безопасности";
                    default -> {
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        body = bytes;
                    }
                }
                if (body != null && body.length > props.getMaxBodyBytes()) {
                    body = java.util.Arrays.copyOf(body, props.getMaxBodyBytes());
                    note = "Тело обрезано до " + props.getMaxBodyBytes() + " байт из " + bodySize;
                }
            } catch (Exception e) {
                note = "Не удалось прочитать тело: " + e.getMessage();
            }
        }
        Object userId = m.getUserID();
        return new ArtemisModel.Message(
                m.getMessageID(),
                userId == null ? null : "ID:" + userId,
                Instant.ofEpochMilli(m.getTimestamp()),
                m.getAddress(),
                m.getPriority(),
                m.isDurable(),
                m.getExpiration() == 0 ? null : Instant.ofEpochMilli(m.getExpiration()),
                type, bodySize, body, note, properties);
    }

    static String bodyType(byte type) {
        return switch (type) {
            case Message.TEXT_TYPE -> "TEXT";
            case Message.BYTES_TYPE -> "BYTES";
            case Message.MAP_TYPE -> "MAP";
            case Message.OBJECT_TYPE -> "OBJECT";
            case Message.STREAM_TYPE -> "STREAM";
            default -> "DEFAULT";
        };
    }

    private static boolean matches(ArtemisModel.Message m, BrowseRequest req) {
        if (notBlank(req.bodyFilter()) && !contains(m.body() == null ? null : MessageFormat.text(m.body()), req.bodyFilter())) {
            return false;
        }
        if (notBlank(req.idFilter()) && !contains(m.userId() + " " + m.messageId(), req.idFilter())) return false;
        if (notBlank(req.propertyFilter())) {
            return m.properties().entrySet().stream()
                    .anyMatch(e -> contains(e.getKey() + "=" + e.getValue(), req.propertyFilter()));
        }
        return true;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
