package ru.ops.console.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.ops.console.config.ConsoleProperties;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryGroupRoleMapperTest {

    private final DirectoryGroupRoleMapper mapper = new DirectoryGroupRoleMapper(props());

    private static ConsoleProperties props() {
        ConsoleProperties p = new ConsoleProperties();
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("viewer", List.of("SG-Viewers"));
        m.put("OPERATOR", List.of("SG-Operators", "SG-Duty"));
        m.put("ADMIN", List.of("SG-Admins"));
        p.getSecurity().setRoleMapping(m);
        return p;
    }

    private List<String> map(String... groups) {
        List<GrantedAuthority> in = Arrays.stream(groups).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        return mapper.mapAuthorities(in).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void highestRoleExpandsHierarchy() {
        assertThat(map("SG-Admins")).containsExactly("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
        assertThat(map("SG-Duty")).containsExactly("ROLE_OPERATOR", "ROLE_VIEWER");
        assertThat(map("SG-Viewers", "SG-Admins")).containsExactly("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_VIEWER");
    }

    @Test
    void groupNamesFromAdAreNormalized() {
        // The Spring AD provider returns the CN, the LDAP provider — ROLE_<CN in upper case>, sometimes the full DN
        assertThat(map("ROLE_SG-VIEWERS")).containsExactly("ROLE_VIEWER");
        assertThat(map("CN=SG-Operators,OU=Groups,DC=corp,DC=local")).containsExactly("ROLE_OPERATOR", "ROLE_VIEWER");
    }

    @Test
    void unknownGroupsGiveNoRoles() {
        assertThat(map("Domain Users", "SG-Other")).isEmpty();
        assertThat(map()).isEmpty();
    }

    @Test
    void extractCn() {
        assertThat(DirectoryGroupRoleMapper.extractCn("cn=abc,ou=x")).isEqualTo("abc");
        assertThat(DirectoryGroupRoleMapper.extractCn("CN=abc")).isEqualTo("abc");
        assertThat(DirectoryGroupRoleMapper.extractCn(" plain ")).isEqualTo("plain");
    }
}
