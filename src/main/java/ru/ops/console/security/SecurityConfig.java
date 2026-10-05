package ru.ops.console.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.ui.LoginView;

import java.util.Map;

/**
 * Security: Vaadin login form + authentication against Active Directory or LDAP.
 * Roles are assigned by directory group membership (see {@link DirectoryGroupRoleMapper}).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends VaadinWebSecurity {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/health/**")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/actuator/info")).permitAll()
                .requestMatchers(AntPathRequestMatcher.antMatcher("/" + SessionExpiredController.PATH)).permitAll());
        super.configure(http);
        setLoginView(http, LoginView.class);
        // A specific reason where the password is known to be correct; a generic message otherwise
        ExceptionMappingAuthenticationFailureHandler failure = new ExceptionMappingAuthenticationFailureHandler();
        failure.setDefaultFailureUrl("/login?error");
        failure.setExceptionMappings(Map.of(
                ConsoleAuthenticationProvider.NoConsoleAccessException.class.getName(), "/login?error=no-access",
                CredentialsExpiredException.class.getName(), "/login?error=password-expired"));
        http.formLogin(form -> form.failureHandler(failure));
    }

    /** Every login failure (including "no console access") is published as an event and audited. */
    @Bean
    public AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher publisher) {
        DefaultAuthenticationEventPublisher p = new DefaultAuthenticationEventPublisher(publisher);
        p.setDefaultAuthenticationFailureEvent(AuthenticationFailureBadCredentialsEvent.class);
        return p;
    }

    @Bean
    public DirectoryGroupRoleMapper directoryGroupRoleMapper(ConsoleProperties props) {
        return new DirectoryGroupRoleMapper(props);
    }

    @Bean
    public AuthenticationProvider directoryAuthenticationProvider(ConsoleProperties props,
                                                                  DirectoryGroupRoleMapper roleMapper) throws Exception {
        ConsoleProperties.Ldap ldap = props.getSecurity().getLdap();
        return new ConsoleAuthenticationProvider(switch (props.getSecurity().getMode()) {
            case AD -> activeDirectory(ldap, roleMapper);
            case LDAP -> genericLdap(ldap, roleMapper);
        });
    }

    private AuthenticationProvider activeDirectory(ConsoleProperties.Ldap ldap, DirectoryGroupRoleMapper roleMapper) {
        ActiveDirectoryLdapAuthenticationProvider provider =
                new ActiveDirectoryLdapAuthenticationProvider(ldap.getDomain(), ldap.getUrl(), ldap.getRootDn());
        provider.setConvertSubErrorCodesToExceptions(true);
        provider.setUseAuthenticationRequestCredentials(true);
        if (StringUtils.hasText(ldap.getAdSearchFilter())) {
            provider.setSearchFilter(ldap.getAdSearchFilter());
        }
        provider.setAuthoritiesMapper(roleMapper);
        return provider;
    }

    private AuthenticationProvider genericLdap(ConsoleProperties.Ldap ldap, DirectoryGroupRoleMapper roleMapper)
            throws Exception {
        DefaultSpringSecurityContextSource contextSource = new DefaultSpringSecurityContextSource(ldap.getUrl());
        if (StringUtils.hasText(ldap.getManagerDn())) {
            contextSource.setUserDn(ldap.getManagerDn());
            contextSource.setPassword(ldap.getManagerPassword());
        }
        contextSource.afterPropertiesSet();

        FilterBasedLdapUserSearch userSearch =
                new FilterBasedLdapUserSearch(ldap.getUserSearchBase(), ldap.getUserSearchFilter(), contextSource);
        userSearch.setSearchSubtree(true);

        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(userSearch);

        DefaultLdapAuthoritiesPopulator populator =
                new DefaultLdapAuthoritiesPopulator(contextSource, ldap.getGroupSearchBase());
        populator.setGroupSearchFilter(ldap.getGroupSearchFilter());
        populator.setGroupRoleAttribute("cn");
        populator.setRolePrefix("");
        populator.setConvertToUpperCase(false);
        populator.setSearchSubtree(true);
        populator.setIgnorePartialResultException(true);

        LdapAuthenticationProvider provider = new LdapAuthenticationProvider(authenticator, populator);
        provider.setAuthoritiesMapper(roleMapper);
        return provider;
    }
}
