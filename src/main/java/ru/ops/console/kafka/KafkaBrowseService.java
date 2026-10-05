package ru.ops.console.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Service;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConcurrencyLimit;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.kafka.KafkaModel.BrowseRequest;
import ru.ops.console.kafka.KafkaModel.BrowseResult;
import ru.ops.console.kafka.KafkaModel.Header;
import ru.ops.console.kafka.KafkaModel.Message;
import ru.ops.console.kafka.KafkaModel.StartFrom;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Reads messages from a topic without a consumer group: assign + seek, offsets are never committed.
 * <p>
 * LATEST mode reads the "tail" of every partition and returns the N newest messages
 * (with a filter — the N newest matching ones within the scanned window).
 */
@Service
public class KafkaBrowseService {

    private final KafkaClients clients;
    private final AuditService audit;
    private final ConsoleProperties.Browse props;
    private final ConcurrencyLimit limit;

    public KafkaBrowseService(KafkaClients clients, AuditService audit, ConsoleProperties props) {
        this.clients = clients;
        this.audit = audit;
        this.props = props.getKafka().getBrowse();
        this.limit = new ConcurrencyLimit("просмотр Kafka", props.getLimits().getMaxParallelBrowse(),
                props.getLimits().getWaitMs());
    }

    public int maxMessages() {
        return props.getMaxMessages();
    }

    /**
     * May take a while — call from a background thread, the user is passed explicitly.
     */
    public BrowseResult browse(String user, BrowseRequest req) {
        return limit.run(() -> doBrowse(user, req));
    }

    private BrowseResult doBrowse(String user, BrowseRequest req) {
        long started = System.currentTimeMillis();
        int limit = Math.max(1, Math.min(req.limit(), props.getMaxMessages()));
        boolean filtered = notBlank(req.keyFilter()) || notBlank(req.valueFilter()) || notBlank(req.headerFilter());

        try (KafkaConsumer<byte[], byte[]> consumer = clients.newBrowseConsumer()) {
            Duration apiTimeout = Duration.ofMillis(clients.props().getRequestTimeoutMs());
            List<PartitionInfo> infos = consumer.partitionsFor(req.topic(), apiTimeout);
            if (infos == null || infos.isEmpty()) throw new KafkaException("Топик " + req.topic() + " не найден");

            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo pi : infos) {
                if (req.partition() == null || req.partition() == pi.partition()) {
                    tps.add(new TopicPartition(req.topic(), pi.partition()));
                }
            }
            if (tps.isEmpty()) throw new KafkaException("Партиция " + req.partition() + " не существует");

            consumer.assign(tps);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, apiTimeout);
            Map<TopicPartition, Long> end = consumer.endOffsets(tps, apiTimeout);

            // Where to start and where to stop in every partition
            Map<TopicPartition, Long> start = new HashMap<>();
            Map<TopicPartition, Long> timeStart = Map.of();
            if (req.startFrom() == StartFrom.TIMESTAMP) {
                Map<TopicPartition, Long> query = new HashMap<>();
                long ts = req.timestamp() == null ? System.currentTimeMillis() : req.timestamp().toEpochMilli();
                tps.forEach(tp -> query.put(tp, ts));
                Map<TopicPartition, OffsetAndTimestamp> found = consumer.offsetsForTimes(query, apiTimeout);
                timeStart = new HashMap<>();
                for (TopicPartition tp : tps) {
                    OffsetAndTimestamp oat = found.get(tp);
                    timeStart.put(tp, oat == null ? end.get(tp) : oat.offset());
                }
            }
            for (TopicPartition tp : tps) {
                long b = begin.get(tp);
                long e = end.get(tp);
                long s = switch (req.startFrom()) {
                    case EARLIEST -> b;
                    case LATEST -> Math.max(b, e - (filtered ? props.getMaxScanPerPartition() : limit));
                    case OFFSET -> Math.min(e, Math.max(b, req.offset() == null ? 0 : req.offset()));
                    case TIMESTAMP -> timeStart.get(tp);
                };
                start.put(tp, s);
            }

            Set<TopicPartition> active = new HashSet<>();
            for (TopicPartition tp : tps) {
                if (start.get(tp) < end.get(tp)) {
                    consumer.seek(tp, start.get(tp));
                    active.add(tp);
                }
            }
            consumer.pause(difference(tps, active));

            Comparator<Message> byTime = Comparator.comparing(Message::timestamp)
                    .thenComparingInt(Message::partition).thenComparingLong(Message::offset);
            boolean tail = req.startFrom() == StartFrom.LATEST;
            // Tail mode scans up to max-scan-per-partition records: keep only the N newest matches in memory
            // (min-heap), otherwise one filtered request could hold hundreds of thousands of messages.
            PriorityQueue<Message> newest = new PriorityQueue<>(byTime);
            List<Message> collected = new ArrayList<>();
            Map<TopicPartition, Long> scannedPerPartition = new HashMap<>();
            long scanned = 0;
            boolean timedOut = false;
            boolean truncated = false;
            long deadline = started + props.getTimeoutMs();

            while (!active.isEmpty()) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true;
                    break;
                }
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<byte[], byte[]> r : records) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    if (!active.contains(tp) || r.offset() >= end.get(tp)) continue;
                    scanned++;
                    long perPartition = scannedPerPartition.merge(tp, 1L, Long::sum);
                    Message m = toMessage(r);
                    if (matches(m, req)) {
                        if (tail) {
                            newest.add(m);
                            if (newest.size() > limit) newest.poll();
                        } else {
                            collected.add(m);
                        }
                    }
                    if (perPartition >= props.getMaxScanPerPartition()) {
                        truncated = true;
                        active.remove(tp);
                        consumer.pause(List.of(tp));
                    }
                }
                // Partitions read up to the captured end offset
                for (TopicPartition tp : new ArrayList<>(active)) {
                    if (consumer.position(tp, apiTimeout) >= end.get(tp)) {
                        active.remove(tp);
                        consumer.pause(List.of(tp));
                    }
                }
                if (!tail && collected.size() >= limit) {
                    truncated = !active.isEmpty();
                    break;
                }
            }

            List<Message> result;
            if (tail) {
                result = new ArrayList<>(newest);
                result.sort(byTime.reversed());
            } else {
                collected.sort(byTime);
                result = collected.size() > limit ? new ArrayList<>(collected.subList(0, limit)) : collected;
            }

            long elapsed = System.currentTimeMillis() - started;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("partition", req.partition());
            details.put("from", req.startFrom().name());
            details.put("offset", req.offset());
            details.put("timestamp", req.timestamp() == null ? null : req.timestamp().toString());
            details.put("keyFilter", req.keyFilter());
            details.put("valueFilter", req.valueFilter());
            details.put("returned", result.size());
            details.put("scanned", scanned);
            audit.record(user, AuditAction.KAFKA_BROWSE, req.topic(), null, true, details);
            return new BrowseResult(result, scanned, truncated, timedOut, elapsed);
        } catch (KafkaException e) {
            throw e;
        } catch (Exception e) {
            throw KafkaException.wrap("Чтение топика " + req.topic(), e);
        }
    }

    private static Set<TopicPartition> difference(List<TopicPartition> all, Set<TopicPartition> active) {
        Set<TopicPartition> s = new HashSet<>(all);
        s.removeAll(active);
        return s;
    }

    private static Message toMessage(ConsumerRecord<byte[], byte[]> r) {
        List<Header> headers = new ArrayList<>();
        r.headers().forEach(h -> headers.add(new Header(h.key(), h.value())));
        return new Message(r.topic(), r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()),
                String.valueOf(r.timestampType()), r.key(), r.value(), headers);
    }

    private static boolean matches(Message m, BrowseRequest req) {
        if (notBlank(req.keyFilter()) && !contains(MessageFormat.text(m.key()), req.keyFilter())) return false;
        if (notBlank(req.valueFilter()) && !contains(MessageFormat.text(m.value()), req.valueFilter())) return false;
        if (notBlank(req.headerFilter())) {
            boolean any = false;
            for (Header h : m.headers()) {
                if (contains(h.key() + "=" + MessageFormat.text(h.value()), req.headerFilter())) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
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
