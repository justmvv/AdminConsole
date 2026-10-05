package ru.ops.console.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ru.ops.console.db.DbException;
import ru.ops.console.kafka.KafkaException;

import java.nio.file.Path;
import java.sql.SQLException;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

class SafeLogTest {

    private static final String ROW = "Failing row contains (42, 40702810000000000123, Иванов Иван)";

    @Test
    void databaseErrorsAreLoggedWithoutTheirText() {
        SQLException sql = new SQLException("ERROR: null value in column \"step_name\"\n  Detail: " + ROW, "23502");
        String logged = SafeLog.describe(new DbException(sql));
        assertThat(logged).contains("DbException").contains("23502").doesNotContain("40702810").doesNotContain("Иванов");

        assertThat(SafeLog.describe(new DataIntegrityViolationException("x", sql)))
                .contains("23502").doesNotContain("40702810");
        assertThat(SafeLog.describe(new RuntimeException("wrapper", sql))).doesNotContain("40702810");
    }

    @Test
    void otherErrorsKeepTheirMessage() {
        assertThat(SafeLog.describe(new KafkaException("Брокер недоступен"))).contains("Брокер недоступен");
    }

    @Test
    void auditLogNeverGoesToTheGeneralLog() throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("src/main/resources/logback-spring.xml").toFile());
        NodeList loggers = doc.getElementsByTagName("logger");
        Element audit = null;
        for (int i = 0; i < loggers.getLength(); i++) {
            Element l = (Element) loggers.item(i);
            if ("AUDIT".equals(l.getAttribute("name"))) audit = l;
        }
        assertThat(audit).isNotNull();
        assertThat(audit.getAttribute("additivity")).isEqualTo("false");
    }
}
