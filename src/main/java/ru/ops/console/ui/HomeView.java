package ru.ops.console.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import ru.ops.console.artemis.ArtemisModel.QueueList;
import ru.ops.console.artemis.ArtemisQueueService;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.db.DbMetadataService;
import ru.ops.console.kafka.KafkaAdminService;
import ru.ops.console.kafka.KafkaModel.ClusterInfo;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.common.Ui;

import java.util.LinkedHashMap;
import java.util.Map;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Обзор")
@PermitAll
public class HomeView extends VerticalLayout {

    public HomeView(ConsoleProperties props, DbMetadataService db, KafkaAdminService kafka,
                    ArtemisQueueService artemis) {
        add(new H2("Обзор"));

        if (!CurrentUser.hasRole(Roles.VIEWER)) {
            Paragraph p = new Paragraph("У вашей учётной записи нет ролей консоли. Обратитесь к администратору "
                    + "для включения в группу каталога (VIEWER / OPERATOR / ADMIN).");
            p.getStyle().set("color", "var(--lumo-error-text-color)");
            add(p);
            return;
        }

        FlexLayout cards = new FlexLayout();
        cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cards.getStyle().set("gap", "var(--lumo-space-m)");
        add(cards);

        // background task errors are shown as notifications; the card keeps its "Loading…" text
        if (props.getFeatures().isDb()) {
            Div dbCard = card("PostgreSQL", new Span("Загрузка…"));
            cards.add(dbCard);
            Ui.background(this, db::serverInfo, info -> replaceBody(dbCard, kv(info)), null);
        }

        if (props.getFeatures().isKafka()) {
            Div kafkaCard = card("Kafka", new Span("Загрузка…"));
            cards.add(kafkaCard);
            Ui.background(this, kafka::cluster, (ClusterInfo c) -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("Bootstrap", props.getKafka().getBootstrapServers());
                m.put("Cluster ID", c.clusterId());
                m.put("Брокеров", String.valueOf(c.brokers()));
                m.put("Контроллер", c.controller());
                m.put("Узлы", String.join("\n", c.nodes()));
                replaceBody(kafkaCard, kv(m));
            }, null);
        }

        if (props.getFeatures().isArtemis()) {
            Div artemisCard = card("Artemis", new Span("Загрузка…"));
            cards.add(artemisCard);
            Ui.background(this, artemis::queues, (QueueList list) -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("Брокер", artemis.url());
                m.put("Учётная запись", artemis.user());
                m.put("Очередей видно", String.valueOf(list.queues().size()));
                m.put("Management", list.managementNote() == null ? "доступен" : "нет (только просмотр)");
                replaceBody(artemisCard, kv(m));
            }, null);
        }

        Map<String, String> me = new LinkedHashMap<>();
        me.put("Пользователь", CurrentUser.name());
        me.put("Роли", String.join(", ", CurrentUser.roles()));
        me.put("Стенд", props.getUi().getEnvironmentName());
        if (props.isReadOnly()) me.put("Режим", "только чтение (console.read-only)");
        String features = java.util.Arrays.stream(Feature.values())
                .filter(f -> props.getFeatures().isEnabled(f)).map(Feature::label)
                .collect(java.util.stream.Collectors.joining(", "));
        me.put("Разделы", features.isEmpty() ? "нет (все выключены в console.features)" : features);
        if (props.getFeatures().isDb()) {
            me.put("Вставка в БД", props.getDb().getInsert().isEnabled()
                    ? String.join(", ", props.getDb().getInsert().getAllowedTables()) : "выключена");
        }
        if (props.getFeatures().isKafka()) {
            me.put("Отправка в Kafka", props.getKafka().getProduce().isEnabled()
                    ? String.join(", ", props.getKafka().getProduce().getAllowedTopics()) : "выключена");
        }
        if (props.getFeatures().isArtemis()) {
            me.put("Отправка в Artemis", props.getArtemis().getProduce().isEnabled()
                    ? String.join(", ", props.getArtemis().getProduce().getAllowedAddresses()) : "выключена");
        }
        cards.add(card("Сессия", kv(me)));
    }

    private static Div card(String title, Component body) {
        Div d = new Div(new H3(title), body);
        d.getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "var(--lumo-space-m)")
                .set("min-width", "320px")
                .set("max-width", "560px");
        return d;
    }

    private static void replaceBody(Div card, Component body) {
        card.getChildren().skip(1).toList().forEach(card::remove);
        card.add(body);
    }

    private static Component kv(Map<String, String> m) {
        Div grid = new Div();
        grid.getStyle().set("display", "grid").set("grid-template-columns", "max-content 1fr")
                .set("gap", "4px 16px");
        m.forEach((k, v) -> {
            Span key = new Span(k);
            key.getStyle().set("color", "var(--lumo-secondary-text-color)");
            Span val = Ui.mono(v == null ? "" : v);
            val.getStyle().set("white-space", "pre-wrap").set("word-break", "break-all");
            grid.add(key, val);
        });
        return grid;
    }
}
