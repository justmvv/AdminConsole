package ru.ops.console.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobsTest {

    @Test
    void exactAndWildcard() {
        assertThat(Globs.matches("orch.payment", "orch.payment")).isTrue();
        assertThat(Globs.matches("orch.payment", "orch.*")).isTrue();
        assertThat(Globs.matches("orchx.payment", "orch.*")).isFalse();
        assertThat(Globs.matches("anything", "*")).isTrue();
        assertThat(Globs.matches("payments.in.dlq", "payments.*.dlq")).isTrue();
        assertThat(Globs.matches("payments.in.retry", "payments.*.dlq")).isFalse();
    }

    @Test
    void caseInsensitive() {
        assertThat(Globs.matches("ORCH.Payment", "orch.payment")).isTrue();
    }

    @Test
    void regexMetacharactersAreLiteral() {
        // a dot in the mask is a dot, not "any character"
        assertThat(Globs.matches("orchXpayment", "orch.payment")).isFalse();
        assertThat(Globs.matches("a+b", "a+b")).isTrue();
        assertThat(Globs.matches("aab", "a+b")).isFalse();
    }

    @Test
    void matchesAny() {
        assertThat(Globs.matchesAny("orch.outbox", List.of("orch.retry_task", " orch.outbox "))).isTrue();
        assertThat(Globs.matchesAny("orch.payment", List.of())).isFalse();
        assertThat(Globs.matchesAny("orch.payment", null)).isFalse();
        assertThat(Globs.matchesAny(null, List.of("*"))).isFalse();
    }
}
