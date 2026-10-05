package ru.ops.console.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.stereotype.Service;
import ru.ops.console.kafka.KafkaModel.ClusterInfo;
import ru.ops.console.kafka.KafkaModel.ConfigEntryInfo;
import ru.ops.console.kafka.KafkaModel.GroupDetails;
import ru.ops.console.kafka.KafkaModel.GroupInfo;
import ru.ops.console.kafka.KafkaModel.GroupMember;
import ru.ops.console.kafka.KafkaModel.GroupOffset;
import ru.ops.console.kafka.KafkaModel.PartitionInfo;
import ru.ops.console.kafka.KafkaModel.TopicDetails;
import ru.ops.console.kafka.KafkaModel.TopicInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Read-only access to cluster metadata: topics, partitions, configuration, consumer groups and lag.
 */
@Service
public class KafkaAdminService {

    private final KafkaClients clients;

    public KafkaAdminService(KafkaClients clients) {
        this.clients = clients;
    }

    private long timeout() {
        return clients.props().getRequestTimeoutMs();
    }

    private <T> T get(KafkaFuture<T> future, String action) {
        try {
            return future.get(timeout(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw KafkaException.wrap(action, e);
        }
    }

    // ---------------------------------------------------------------- cluster

    public ClusterInfo cluster() {
        Admin admin = clients.admin();
        DescribeClusterResult r = admin.describeCluster();
        Collection<Node> nodes = get(r.nodes(), "Описание кластера");
        Node controller = get(r.controller(), "Описание кластера");
        String id = get(r.clusterId(), "Описание кластера");
        return new ClusterInfo(id, nodes.size(), controller == null ? "-" : node(controller),
                nodes.stream().sorted(Comparator.comparingInt(Node::id)).map(KafkaAdminService::node).toList());
    }

    private static String node(Node n) {
        return n == null ? "-" : n.id() + " (" + n.host() + ":" + n.port() + ")";
    }

    // ----------------------------------------------------------------- topics

    public List<String> topicNames() {
        Collection<TopicListing> listings = get(
                clients.admin().listTopics(new ListTopicsOptions().listInternal(!clients.props().isHideInternalTopics()))
                        .listings(), "Список топиков");
        return listings.stream().map(TopicListing::name).sorted().toList();
    }

    public List<TopicInfo> topics() {
        Admin admin = clients.admin();
        List<String> names = topicNames();
        if (names.isEmpty()) return List.of();
        Map<String, TopicDescription> desc = get(admin.describeTopics(names).allTopicNames(), "Описание топиков");

        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (TopicDescription d : desc.values()) {
            for (TopicPartitionInfo p : d.partitions()) {
                TopicPartition tp = new TopicPartition(d.name(), p.partition());
                earliest.put(tp, OffsetSpec.earliest());
                latest.put(tp, OffsetSpec.latest());
            }
        }
        Map<TopicPartition, Long> begin = offsets(admin, earliest);
        Map<TopicPartition, Long> end = offsets(admin, latest);

        List<TopicInfo> result = new ArrayList<>();
        for (TopicDescription d : desc.values()) {
            long msgs = 0;
            for (TopicPartitionInfo p : d.partitions()) {
                TopicPartition tp = new TopicPartition(d.name(), p.partition());
                msgs += Math.max(0, end.getOrDefault(tp, 0L) - begin.getOrDefault(tp, 0L));
            }
            int rf = d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size();
            result.add(new TopicInfo(d.name(), d.partitions().size(), rf, d.isInternal(), msgs));
        }
        result.sort(Comparator.comparing(TopicInfo::name));
        return result;
    }

    /** Partition offsets; if some partitions fail, returns what was retrieved. */
    private Map<TopicPartition, Long> offsets(Admin admin, Map<TopicPartition, OffsetSpec> request) {
        if (request.isEmpty()) return Map.of();
        ListOffsetsResult r = admin.listOffsets(request);
        Map<TopicPartition, Long> result = new HashMap<>();
        for (TopicPartition tp : request.keySet()) {
            try {
                result.put(tp, r.partitionResult(tp).get(timeout(), TimeUnit.MILLISECONDS).offset());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw KafkaException.wrap("Получение офсетов", e);
                }
                // skip the partition (e.g. its leader is unavailable)
            }
        }
        return result;
    }

    public TopicDetails topic(String topic) {
        Admin admin = clients.admin();
        Map<String, TopicDescription> desc = get(admin.describeTopics(List.of(topic)).allTopicNames(),
                "Описание топика " + topic);
        TopicDescription d = desc.get(topic);
        if (d == null) throw new KafkaException("Топик " + topic + " не найден");

        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (TopicPartitionInfo p : d.partitions()) {
            TopicPartition tp = new TopicPartition(topic, p.partition());
            earliest.put(tp, OffsetSpec.earliest());
            latest.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, Long> begin = offsets(admin, earliest);
        Map<TopicPartition, Long> end = offsets(admin, latest);

        List<PartitionInfo> partitions = new ArrayList<>();
        for (TopicPartitionInfo p : d.partitions()) {
            TopicPartition tp = new TopicPartition(topic, p.partition());
            partitions.add(new PartitionInfo(
                    p.partition(),
                    p.leader() == null ? "нет лидера" : String.valueOf(p.leader().id()),
                    p.replicas().stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(",")),
                    p.isr().stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(",")),
                    begin.getOrDefault(tp, -1L), end.getOrDefault(tp, -1L),
                    p.isr().size() < p.replicas().size()));
        }
        partitions.sort(Comparator.comparingInt(PartitionInfo::partition));

        List<ConfigEntryInfo> configs = new ArrayList<>();
        try {
            ConfigResource res = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Map<ConfigResource, Config> cfg = get(admin.describeConfigs(List.of(res)).all(), "Конфигурация топика");
            Config config = cfg.get(res);
            if (config != null) {
                for (ConfigEntry e : config.entries()) {
                    boolean isDefault = e.source() == ConfigEntry.ConfigSource.DEFAULT_CONFIG
                            || e.source() == ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG
                            || e.source() == ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG;
                    configs.add(new ConfigEntryInfo(e.name(), e.isSensitive() ? "******" : e.value(),
                            String.valueOf(e.source()), isDefault, e.isSensitive()));
                }
            }
        } catch (KafkaException e) {
            configs.add(new ConfigEntryInfo("(ошибка)", e.getMessage(), "", false, false));
        }
        configs.sort(Comparator.comparing(ConfigEntryInfo::isDefault).thenComparing(ConfigEntryInfo::name));
        return new TopicDetails(topic, d.isInternal(), partitions, configs);
    }

    // --------------------------------------------------------- consumer groups

    public List<GroupInfo> groups() {
        Admin admin = clients.admin();
        Collection<ConsumerGroupListing> listings = get(admin.listConsumerGroups().all(), "Список consumer groups");
        List<String> ids = listings.stream().map(ConsumerGroupListing::groupId).sorted().toList();
        if (ids.isEmpty()) return List.of();

        Map<String, ConsumerGroupDescription> desc = get(admin.describeConsumerGroups(ids).all(),
                "Описание consumer groups");

        Map<String, ListConsumerGroupOffsetsSpec> specs = new HashMap<>();
        for (String id : ids) specs.put(id, new ListConsumerGroupOffsetsSpec());
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed =
                get(admin.listConsumerGroupOffsets(specs).all(), "Офсеты consumer groups");

        Map<TopicPartition, OffsetSpec> latestReq = new HashMap<>();
        committed.values().forEach(m -> m.keySet().forEach(tp -> latestReq.put(tp, OffsetSpec.latest())));
        Map<TopicPartition, Long> end = offsets(admin, latestReq);

        List<GroupInfo> result = new ArrayList<>();
        for (String id : ids) {
            ConsumerGroupDescription d = desc.get(id);
            Map<TopicPartition, OffsetAndMetadata> offs = committed.getOrDefault(id, Map.of());
            long lag = 0;
            Set<String> topics = new TreeSet<>();
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : offs.entrySet()) {
                topics.add(e.getKey().topic());
                Long endOffset = end.get(e.getKey());
                if (e.getValue() != null && endOffset != null) {
                    lag += Math.max(0, endOffset - e.getValue().offset());
                }
            }
            result.add(new GroupInfo(id,
                    d == null ? "?" : String.valueOf(d.state()),
                    d == null ? 0 : d.members().size(),
                    lag, offs.size(), List.copyOf(topics),
                    d == null ? "-" : node(d.coordinator())));
        }
        return result;
    }

    public GroupDetails group(String groupId) {
        Admin admin = clients.admin();
        Map<String, ConsumerGroupDescription> descMap =
                get(admin.describeConsumerGroups(List.of(groupId)).all(), "Описание группы " + groupId);
        ConsumerGroupDescription d = descMap.get(groupId);
        Map<TopicPartition, OffsetAndMetadata> committed =
                get(admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata(), "Офсеты группы");

        List<GroupMember> members = new ArrayList<>();
        Map<TopicPartition, String> owner = new HashMap<>();
        Set<TopicPartition> all = new HashSet<>(committed.keySet());
        if (d != null) {
            for (MemberDescription m : d.members()) {
                Set<TopicPartition> tps = m.assignment().topicPartitions();
                tps.forEach(tp -> owner.put(tp, m.clientId()));
                all.addAll(tps);
                String assignment = tps.stream()
                        .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                        .map(tp -> tp.topic() + "-" + tp.partition())
                        .collect(Collectors.joining(", "));
                members.add(new GroupMember(m.consumerId(), m.clientId(), m.host(), assignment));
            }
        }

        Map<TopicPartition, OffsetSpec> latestReq = new HashMap<>();
        all.forEach(tp -> latestReq.put(tp, OffsetSpec.latest()));
        Map<TopicPartition, Long> end = offsets(admin, latestReq);

        List<GroupOffset> offsets = new ArrayList<>();
        long totalLag = 0;
        for (TopicPartition tp : all) {
            OffsetAndMetadata om = committed.get(tp);
            long endOffset = end.getOrDefault(tp, -1L);
            Long lag = (om != null && endOffset >= 0) ? Math.max(0, endOffset - om.offset()) : null;
            if (lag != null) totalLag += lag;
            offsets.add(new GroupOffset(tp.topic(), tp.partition(), om == null ? null : om.offset(), endOffset, lag,
                    owner.get(tp), om == null ? null : om.metadata()));
        }
        offsets.sort(Comparator.comparing(GroupOffset::topic).thenComparingInt(GroupOffset::partition));

        Set<String> topics = offsets.stream().map(GroupOffset::topic).collect(Collectors.toCollection(TreeSet::new));
        GroupInfo info = new GroupInfo(groupId,
                d == null ? "?" : String.valueOf(d.state()),
                members.size(), totalLag, offsets.size(), List.copyOf(topics),
                d == null ? "-" : node(d.coordinator()));
        return new GroupDetails(info, d == null ? "-" : d.partitionAssignor(), members, offsets);
    }
}
