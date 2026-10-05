package ru.ops.console.support;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TestUsers {

    private TestUsers() {
    }

    public static void loginAs(String user, String... roles) {
        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) authorities[i] = "ROLE_" + roles[i];
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, "n/a", AuthorityUtils.createAuthorityList(authorities)));
    }

    public static void logout() {
        SecurityContextHolder.clearContext();
    }
}
