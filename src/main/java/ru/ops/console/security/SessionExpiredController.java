package ru.ops.console.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The browser comes here when the session ended due to inactivity. Logout happens only if the
 * session is really marked by {@link SessionTimeoutService} — a link cannot log out an active user.
 */
@Controller
public class SessionExpiredController {

    public static final String PATH = "session-expired";

    @GetMapping("/" + PATH)
    public String expired(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            if (!Boolean.TRUE.equals(session.getAttribute(SessionTimeoutService.EXPIRED_ATTRIBUTE))) {
                return "redirect:/";
            }
            new SecurityContextLogoutHandler().logout(request, response,
                    SecurityContextHolder.getContext().getAuthentication());
        }
        return "redirect:/login?expired";
    }
}
