package ru.ops.console.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ops.console.config.ConsoleProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD login against the Active Directory emulator (Samba 4, docker/samba-ad).
 * The provider is built exactly as in the application — via {@link SecurityConfig}.
 * Users and passwords — docker/samba-ad/seed.sh.
 */
@Testcontainers
class ActiveDirectoryAuthIT {

    @Container
    static final GenericContainer<?> AD = new GenericContainer<>(
            new ImageFromDockerfile("admin-console-samba-ad", false)
                    .withFileFromPath(".", Path.of("docker/samba-ad")))
            .withCreateContainerCmdModifier(cmd -> cmd.withHostName("dc01"))
            .withExposedPorts(389)
            .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(3)));

    private static AuthenticationProvider provider;

    @BeforeAll
    static void setUp() throws Exception {
        ConsoleProperties props = new ConsoleProperties();
        props.getSecurity().setMode(ConsoleProperties.Security.Mode.AD);
        props.getSecurity().getLdap().setUrl("ldap://" + AD.getHost() + ":" + AD.getMappedPort(389));
        props.getSecurity().getLdap().setDomain("corp.local");
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("VIEWER", List.of("SG-OrchConsole-Viewers"));
        mapping.put("OPERATOR", List.of("SG-OrchConsole-Operators"));
        mapping.put("ADMIN", List.of("SG-OrchConsole-Admins"));
        props.getSecurity().setRoleMapping(mapping);
        provider = new SecurityConfig().directoryAuthenticationProvider(props, new DirectoryGroupRoleMapper(props));
    }

    private static Authentication login(String user, String password) {
        return provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(user, password));
    }

    private static List<String> roles(Authentication a) {
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void rolesFromAdGroups() {
        assertThat(roles(login("ivanov", "Viewer-2026!"))).containsExactly("ROLE_VIEWER");
        assertThat(roles(login("petrov", "Operator-2026!"))).containsExactly("ROLE_OPERATOR", "ROLE_VIEWER");
        assertThat(roles(login("sidorov", "Admin-2026!")))
                .containsExactly("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
    }

    @Test
    void loginIsCaseInsensitiveAndAcceptsUpn() {
        assertThat(login("PETROV", "Operator-2026!").getName()).isEqualToIgnoringCase("petrov");
        assertThat(roles(login("petrov@corp.local", "Operator-2026!"))).contains("ROLE_OPERATOR");
    }

    @Test
    void domainUserWithoutConsoleGroupsIsRejected() {
        assertThatThrownBy(() -> login("smirnov", "Nobody-2026!"))
                .isInstanceOf(ConsoleAuthenticationProvider.NoConsoleAccessException.class);
    }

    @Test
    void nestedGroupsAreNotExpanded() {
        // kuznetsov ∈ Duty-Team ∈ SG-OrchConsole-Operators: roles come only from the direct memberOf
        assertThatThrownBy(() -> login("kuznetsov", "Duty-2026!"))
                .isInstanceOf(ConsoleAuthenticationProvider.NoConsoleAccessException.class);
    }

    @Test
    void accountStates() {
        assertThatThrownBy(() -> login("blocked", "Blocked-2026!")).isInstanceOf(DisabledException.class);
        assertThatThrownBy(() -> login("expired", "Expired-2026!")).isInstanceOf(CredentialsExpiredException.class);
        assertThatThrownBy(() -> login("petrov", "wrong")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> login("nosuchuser", "whatever")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> login("petrov", "")).isInstanceOf(BadCredentialsException.class);
    }
}
