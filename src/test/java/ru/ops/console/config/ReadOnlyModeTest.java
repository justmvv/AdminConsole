package ru.ops.console.config;

import org.junit.jupiter.api.Test;
import ru.ops.console.artemis.ArtemisProduceService;
import ru.ops.console.db.DbMetadataService;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.TableInfo;
import ru.ops.console.db.DbModel.TableKind;
import ru.ops.console.db.DbModel.TableRef;
import ru.ops.console.kafka.KafkaProduceService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** console.read-only switches every write off, whatever the per-feature whitelists say. */
class ReadOnlyModeTest {

    private static ConsoleProperties props(boolean readOnly) {
        ConsoleProperties p = new ConsoleProperties();
        p.setReadOnly(readOnly);
        p.getDb().getInsert().setAllowedTables(List.of("app.*"));
        p.getDb().getUpdate().setAllowedColumns(List.of("app.*"));
        p.getKafka().getProduce().setAllowedTopics(List.of("*"));
        p.getArtemis().getProduce().setAllowedAddresses(List.of("*"));
        return p;
    }

    private static final TableInfo INFO = new TableInfo(new TableRef("app", "task"), TableKind.TABLE, 0, null, true);
    private static final TableDetails TABLE = new TableDetails(INFO, List.of(
            new ColumnInfo(1, "id", "bigint", "bigint", "int8", "N", true, null, "", "", null, List.of(), true, true),
            new ColumnInfo(2, "status", "text", "text", "text", "S", true, null, "", "", null, List.of(), false, true)),
            List.of("id"), List.of(), List.of());

    @Test
    void writesFollowWhitelistsNormally() {
        ConsoleProperties p = props(false);
        DbMetadataService db = new DbMetadataService(null, p);
        assertThat(db.isInsertAllowed(INFO)).isTrue();
        assertThat(db.updatableColumns(TABLE)).extracting(ColumnInfo::name).containsExactly("status");
        assertThat(new KafkaProduceService(null, null, null, p).isTopicAllowed("any")).isTrue();
        assertThat(new ArtemisProduceService(null, null, null, p).isAddressAllowed("any")).isTrue();
    }

    @Test
    void readOnlyModeTurnsEveryWriteOff() {
        ConsoleProperties p = props(true);
        DbMetadataService db = new DbMetadataService(null, p);
        assertThat(db.isInsertAllowed(INFO)).isFalse();
        assertThat(db.updatableColumns(TABLE)).isEmpty();
        KafkaProduceService kafka = new KafkaProduceService(null, null, null, p);
        assertThat(kafka.isEnabled()).isFalse();
        assertThat(kafka.isTopicAllowed("any")).isFalse();
        ArtemisProduceService artemis = new ArtemisProduceService(null, null, null, p);
        assertThat(artemis.isEnabled()).isFalse();
        assertThat(artemis.isAddressAllowed("any")).isFalse();
    }
}
