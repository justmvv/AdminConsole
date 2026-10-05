package ru.ops.console.ui.kafka;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.kafka.KafkaAdminService;
import ru.ops.console.kafka.KafkaModel.GroupDetails;
import ru.ops.console.kafka.KafkaModel.GroupMember;
import ru.ops.console.kafka.KafkaModel.GroupOffset;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

@RequiresFeature(Feature.KAFKA)
@Route(value = "kafka/group/:group", layout = MainLayout.class)
@RolesAllowed(Roles.VIEWER)
public class GroupView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private final KafkaAdminService admin;
    private String group = "";

    public GroupView(KafkaAdminService admin) {
        this.admin = admin;
        setSizeFull();
    }

    @Override
    public String getPageTitle() {
        return "Группа " + group;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        group = event.getRouteParameters().get("group").orElse("");
        render();
    }

    private void render() {
        removeAll();
        Button back = new Button(VaadinIcon.ARROW_LEFT.create(),
                e -> UI.getCurrent().navigate(ConsumerGroupsView.class));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        H2 title = new H2(group);
        title.getStyle().set("margin", "0");
        Span summary = new Span("загрузка…");
        Button refresh = new Button("Обновить", VaadinIcon.REFRESH.create(), e -> render());
        HorizontalLayout header = new HorizontalLayout(back, title, summary, refresh);
        header.setAlignItems(FlexComponent.Alignment.CENTER);

        Grid<GroupOffset> offsets = new Grid<>();
        offsets.addColumn(GroupOffset::topic).setHeader("Топик").setAutoWidth(true).setSortable(true)
                .setComparator(GroupOffset::topic);
        offsets.addColumn(GroupOffset::partition).setHeader("P").setAutoWidth(true).setTextAlign(ColumnTextAlign.END);
        offsets.addColumn(o -> o.committed() == null ? "—" : String.valueOf(o.committed())).setHeader("Закоммичено")
                .setAutoWidth(true).setTextAlign(ColumnTextAlign.END);
        offsets.addColumn(GroupOffset::endOffset).setHeader("Конец").setAutoWidth(true)
                .setTextAlign(ColumnTextAlign.END);
        offsets.addComponentColumn(o -> {
            Span s = new Span(o.lag() == null ? "—" : String.format("%,d", o.lag()));
            if (o.lag() != null && o.lag() > 0) s.getStyle().set("color", "var(--lumo-error-text-color)")
                    .set("font-weight", "600");
            return s;
        }).setHeader("Lag").setAutoWidth(true).setTextAlign(ColumnTextAlign.END).setSortable(true)
                .setComparator(o -> o.lag() == null ? -1L : o.lag());
        offsets.addColumn(o -> o.memberClientId() == null ? "не назначена" : o.memberClientId())
                .setHeader("Потребитель (client.id)").setFlexGrow(1);
        offsets.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        offsets.setWidthFull();
        offsets.setHeight("50vh");
        offsets.addItemClickListener(e ->
                UI.getCurrent().navigate(TopicView.class, new RouteParameters("topic", e.getItem().topic())));

        Grid<GroupMember> members = new Grid<>();
        members.addColumn(GroupMember::clientId).setHeader("client.id").setAutoWidth(true);
        members.addColumn(GroupMember::host).setHeader("Хост").setAutoWidth(true);
        members.addColumn(GroupMember::assignment).setHeader("Назначенные партиции").setFlexGrow(1);
        members.addColumn(GroupMember::memberId).setHeader("member.id").setAutoWidth(true);
        members.addThemeVariants(GridVariant.LUMO_COMPACT);
        members.setWidthFull();
        members.setAllRowsVisible(true);

        add(header, new H4("Офсеты и lag по партициям"), offsets, new H4("Участники"), members);

        Ui.background(this, () -> admin.group(group), (GroupDetails d) -> {
            offsets.setItems(d.offsets());
            members.setItems(d.members());
            summary.removeAll();
            summary.setText("");
            summary.add(Ui.badge(d.info().state(), ConsumerGroupsView.stateKind(d.info().state())),
                    new Span("  lag: " + String.format("%,d", d.info().totalLag())
                            + " · участников: " + d.info().members()
                            + " · assignor: " + d.assignor()
                            + " · координатор: " + d.info().coordinator()));
        }, null);
    }
}
