package ru.ops.console.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.util.List;
import ru.ops.console.config.ConsoleProperties;

@Route("login")
@PageTitle("Вход")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private static final String BAD_CREDENTIALS_TITLE = "Неверный логин или пароль";
    private static final String BAD_CREDENTIALS_MESSAGE =
            "Проверьте учётные данные. Доступ выдаётся через группы AD/LDAP.";

    private final LoginForm login = new LoginForm();
    private final LoginI18n i18n;
    private final Div expired = new Div("Сеанс завершён из-за отсутствия действий. Войдите снова.");

    public LoginView(ConsoleProperties props) {
        setSizeFull();
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);

        i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle("Вход");
        i18n.getForm().setUsername("Логин (учётная запись домена)");
        i18n.getForm().setPassword("Пароль");
        i18n.getForm().setSubmit("Войти");
        i18n.getForm().setForgotPassword("");
        i18n.getErrorMessage().setTitle(BAD_CREDENTIALS_TITLE);
        i18n.getErrorMessage().setMessage(BAD_CREDENTIALS_MESSAGE);
        login.setI18n(i18n);
        login.setForgotPasswordButtonVisible(false);
        login.setAction("login");

        Span env = new Span(props.getUi().getEnvironmentName());
        env.getStyle()
                .set("background", props.getUi().getEnvironmentColor())
                .set("color", "white")
                .set("padding", "2px 10px")
                .set("border-radius", "8px")
                .set("font-weight", "600");

        expired.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)");
        expired.setVisible(false);

        add(new H1(props.getUi().getTitle()), env, expired, login);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var params = event.getLocation().getQueryParameters().getParameters();
        List<String> error = params.get("error");
        if (error != null) {
            String kind = error.isEmpty() ? "" : error.get(0);
            LoginI18n.ErrorMessage msg = i18n.getErrorMessage();
            switch (kind) {
                case "no-access" -> {
                    msg.setTitle("Нет доступа к консоли");
                    msg.setMessage("Учётная запись не входит ни в одну группу консоли. Запросите доступ "
                            + "через заявку на включение в группу AD.");
                }
                case "password-expired" -> {
                    msg.setTitle("Требуется сменить пароль");
                    msg.setMessage("Смените пароль учётной записи домена (например, при входе в Windows) "
                            + "и войдите снова.");
                }
                default -> {
                    msg.setTitle(BAD_CREDENTIALS_TITLE);
                    msg.setMessage(BAD_CREDENTIALS_MESSAGE);
                }
            }
            login.setI18n(i18n);
        }
        login.setError(error != null);
        expired.setVisible(params.containsKey("expired"));
    }
}
