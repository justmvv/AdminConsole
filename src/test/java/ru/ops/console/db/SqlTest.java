package ru.ops.console.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTest {

    @Test
    void identAlwaysQuotesAndDoublesQuotes() {
        assertThat(Sql.ident("payment")).isEqualTo("\"payment\"");
        assertThat(Sql.ident("Weird\"Name")).isEqualTo("\"Weird\"\"Name\"");
        assertThat(Sql.ident("x\"; drop table t; --")).isEqualTo("\"x\"\"; drop table t; --\"");
    }

    @Test
    void identRejectsEmptyAndNul() {
        assertThatThrownBy(() -> Sql.ident(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sql.ident("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sql.ident("a\0b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void literal() {
        assertThat(Sql.literal(null)).isEqualTo("NULL");
        assertThat(Sql.literal("O'Brien")).isEqualTo("'O''Brien'");
        assertThat(Sql.literal("a\\b'c")).isEqualTo("E'a\\\\b''c'");
    }

    @Test
    void likeEscape() {
        assertThat(Sql.likeEscape("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }
}
