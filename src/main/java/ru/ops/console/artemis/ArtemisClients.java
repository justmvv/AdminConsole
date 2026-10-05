package ru.ops.console.artemis;

import jakarta.annotation.PreDestroy;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.springframework.stereotype.Component;
import ru.ops.console.config.ConsoleProperties;

/**
 * Connects to Artemis with the core client. Each operation gets its own connection and session
 * (console operations are rare, and this way broker restarts leave no stale connections).
 * Sessions are created with the browsing or the publishing account — each has only its own permissions.
 */
@Component
public class ArtemisClients {

    /** Connection + session, closed together. */
    public record Connection(ClientSessionFactory factory, ClientSession session) implements AutoCloseable {
        @Override
        public void close() {
            try {
                session.close();
            } catch (Exception ignored) {
                // close the connection anyway
            }
            factory.close();
        }
    }

    private final ConsoleProperties.Artemis props;
    private final ConsoleProperties.Features features;
    private volatile ServerLocator locator;

    public ArtemisClients(ConsoleProperties props) {
        this.props = props.getArtemis();
        this.features = props.getFeatures();
    }

    public ConsoleProperties.Artemis props() {
        return props;
    }

    private ServerLocator locator() throws Exception {
        if (!features.isArtemis()) {
            throw new ArtemisException("Раздел Artemis выключен в настройках (console.features.artemis=false)");
        }
        ServerLocator l = locator;
        if (l == null) {
            synchronized (this) {
                if (locator == null) {
                    ServerLocator created = ActiveMQClient.createServerLocator(props.getUrl());
                    created.setCallTimeout(props.getRequestTimeoutMs());
                    created.setConnectionTTL(Math.max(60_000, props.getRequestTimeoutMs() * 2L));
                    created.setInitialConnectAttempts(1);
                    created.setReconnectAttempts(0);
                    // send errors (including permission denials) are reported synchronously, in response to send
                    created.setBlockOnDurableSend(true);
                    created.setBlockOnNonDurableSend(true);
                    locator = created;
                }
                l = locator;
            }
        }
        return l;
    }

    /** Session for browsing and management. */
    public Connection browseConnection() throws Exception {
        return connect(props.getUser(), props.getPassword());
    }

    /** Session for publishing — a separate account if configured. */
    public Connection produceConnection() throws Exception {
        ConsoleProperties.ArtemisProduce p = props.getProduce();
        return p.getUser() != null && !p.getUser().isBlank()
                ? connect(p.getUser(), p.getPassword())
                : connect(props.getUser(), props.getPassword());
    }

    private Connection connect(String user, String password) throws Exception {
        ClientSessionFactory factory = locator().createSessionFactory();
        try {
            ClientSession session = factory.createSession(user, password, false, true, true, false, 0);
            return new Connection(factory, session);
        } catch (Exception e) {
            factory.close();
            throw e;
        }
    }

    @PreDestroy
    void close() {
        if (locator != null) locator.close();
    }
}
