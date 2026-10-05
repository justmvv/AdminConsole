package ru.ops.console.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import ru.ops.console.config.ConsoleProperties;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns directory groups (CN) into console roles according to {@code console.security.role-mapping}.
 * All other groups are dropped so the session carries no unnecessary authorities.
 */
public class DirectoryGroupRoleMapper implements GrantedAuthoritiesMapper {

    private static final Logger log = LoggerFactory.getLogger(DirectoryGroupRoleMapper.class);
    private static final List<String> ORDER = List.of(Roles.ADMIN, Roles.OPERATOR, Roles.VIEWER);

    private final Map<String, List<String>> mapping;

    public DirectoryGroupRoleMapper(ConsoleProperties props) {
        this.mapping = props.getSecurity().getRoleMapping();
        if (mapping.isEmpty()) {
            log.warn("console.security.role-mapping пуст — ни один пользователь не получит роль");
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<String> groups = new LinkedHashSet<>();
        for (GrantedAuthority a : authorities) {
            String name = a.getAuthority();
            if (name == null) continue;
            // Some providers return the full DN — take the CN
            String cn = extractCn(name);
            groups.add(cn.toLowerCase(Locale.ROOT));
        }

        String highest = null;
        for (String role : ORDER) {
            List<String> mapped = findMapping(role);
            if (mapped.stream().anyMatch(g -> groups.contains(g.toLowerCase(Locale.ROOT)))) {
                highest = role;
                break;
            }
        }

        Set<GrantedAuthority> result = new LinkedHashSet<>();
        if (highest != null) {
            for (String role : ORDER.subList(ORDER.indexOf(highest), ORDER.size())) {
                result.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        log.debug("Группы {} → роли {}", groups, result);
        return result;
    }

    private List<String> findMapping(String role) {
        for (Map.Entry<String, List<String>> e : mapping.entrySet()) {
            if (e.getKey().equalsIgnoreCase(role)) {
                return e.getValue() == null ? List.of() : e.getValue();
            }
        }
        return List.of();
    }

    static String extractCn(String value) {
        String v = value.trim();
        if (v.regionMatches(true, 0, "ROLE_", 0, 5)) {
            v = v.substring(5);
        }
        if (v.regionMatches(true, 0, "cn=", 0, 3)) {
            int comma = v.indexOf(',');
            return comma > 0 ? v.substring(3, comma) : v.substring(3);
        }
        return v;
    }
}
