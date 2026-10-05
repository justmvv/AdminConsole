package ru.ops.console.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryAuthenticationException;

/**
 * Wraps the AD/LDAP provider:
 * <ul>
 *   <li>a successful login without a single console role is rejected — otherwise any domain user could log in;</li>
 *   <li>Spring Security reports AD code 773 ("must change password at next logon") as a bad password —
 *       we turn it into {@link CredentialsExpiredException} so the user knows what to do.</li>
 * </ul>
 */
public class ConsoleAuthenticationProvider implements AuthenticationProvider {

    /** AD sub-code: the password must be changed (pwdLastSet = 0). */
    static final String AD_MUST_RESET_PASSWORD = "773";

    private final AuthenticationProvider delegate;

    public ConsoleAuthenticationProvider(AuthenticationProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result;
        try {
            result = delegate.authenticate(authentication);
        } catch (BadCredentialsException e) {
            if (e.getCause() instanceof ActiveDirectoryAuthenticationException ad
                    && AD_MUST_RESET_PASSWORD.equals(ad.getDataCode())) {
                throw new CredentialsExpiredException("Требуется сменить пароль учётной записи домена", e);
            }
            throw e;
        }
        if (result != null && result.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).noneMatch(a -> a.startsWith("ROLE_"))) {
            throw new NoConsoleAccessException("Учётная запись " + result.getName()
                    + " не входит ни в одну группу консоли");
        }
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }

    /** The password is correct, but the account has no access to the console. */
    public static class NoConsoleAccessException extends AuthenticationException {
        public NoConsoleAccessException(String message) {
            super(message);
        }
    }
}
