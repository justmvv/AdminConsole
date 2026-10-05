package ru.ops.console.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Access to the current user from the UI thread (Vaadin handles events in the HTTP request thread,
 * so the SecurityContext is available there). Background tasks started via {@code Ui.background}
 * inherit the caller's context.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String name() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "anonymous" : a.getName();
    }

    public static boolean hasRole(String role) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return false;
        String wanted = "ROLE_" + role;
        for (GrantedAuthority ga : a.getAuthorities()) {
            if (wanted.equals(ga.getAuthority())) return true;
        }
        return false;
    }

    public static List<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return List.of();
        return a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(s -> s.startsWith("ROLE_"))
                .map(s -> s.substring(5))
                .toList();
    }

    /** Service-level guard — duplicates the check in the UI. */
    public static void require(String role) {
        if (!hasRole(role)) {
            throw new AccessDeniedException("Требуется роль " + role);
        }
    }
}
