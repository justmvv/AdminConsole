package ru.ops.console.kafka;

import org.junit.jupiter.api.Test;
import ru.ops.console.kafka.MessageFormat.Format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void autoPrettyPrintsJson() {
        String out = MessageFormat.format(utf8("{\"a\":1,\"b\":[1,2]}"), Format.AUTO);
        assertThat(out).contains("\n").contains("\"a\" : 1");
    }

    @Test
    void autoKeepsPlainTextAndBrokenJson() {
        assertThat(MessageFormat.format(utf8("Платёж принят"), Format.AUTO)).isEqualTo("Платёж принят");
        assertThat(MessageFormat.format(utf8("{not json"), Format.AUTO)).isEqualTo("{not json");
    }

    @Test
    void autoFallsBackToHexForBinary() {
        byte[] bin = {(byte) 0xff, (byte) 0xfe, 0x01};
        assertThat(MessageFormat.format(bin, Format.AUTO)).isEqualTo("fffe01");
        assertThat(MessageFormat.isValidUtf8(bin)).isFalse();
    }

    @Test
    void recognizesSchemaRegistryWireFormat() {
        byte[] data = ByteBuffer.allocate(8).put((byte) 0).putInt(42).put(new byte[]{1, 2, 3}).array();
        assertThat(MessageFormat.format(data, Format.AUTO)).contains("schema id = 42");
        assertThat(MessageFormat.preview(data, 200)).startsWith("[schema-registry id=42]");
    }

    @Test
    void explicitFormats() {
        byte[] d = utf8("hi");
        assertThat(MessageFormat.format(d, Format.HEX)).isEqualTo("6869");
        assertThat(MessageFormat.format(d, Format.BASE64)).isEqualTo("aGk=");
        assertThat(MessageFormat.format(d, Format.TEXT)).isEqualTo("hi");
        assertThat(MessageFormat.format(null, Format.TEXT)).isNull();
    }

    @Test
    void previewIsSingleLineAndTruncated() {
        assertThat(MessageFormat.preview(utf8("a\nb\rc"), 100)).isEqualTo("a b c");
        assertThat(MessageFormat.preview(utf8("abcdef"), 3)).isEqualTo("abc…");
        assertThat(MessageFormat.preview(null, 10)).isEqualTo("∅");
    }

    @Test
    void isJson() {
        assertThat(MessageFormat.isJson("{\"x\":1}")).isTrue();
        assertThat(MessageFormat.isJson("  ")).isFalse();
        assertThat(MessageFormat.isJson("{x")).isFalse();
    }
}
