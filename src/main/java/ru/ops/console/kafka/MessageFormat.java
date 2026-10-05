package ru.ops.console.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Renders key/value bytes: UTF-8 text, JSON (pretty), HEX, Base64.
 * AUTO: JSON → text → HEX. Recognizes the Confluent Schema Registry wire format (magic byte 0 + schema id).
 */
public final class MessageFormat {

    public enum Format { AUTO, TEXT, JSON, HEX, BASE64 }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MessageFormat() {
    }

    /** Lenient UTF-8 (for searching and short previews). */
    public static String text(byte[] data) {
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    public static boolean isValidUtf8(byte[] data) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    public static String format(byte[] data, Format format) {
        if (data == null) return null;
        return switch (format) {
            case TEXT -> text(data);
            case HEX -> hex(data);
            case BASE64 -> Base64.getEncoder().encodeToString(data);
            case JSON -> prettyJson(text(data));
            case AUTO -> auto(data);
        };
    }

    /** Single-line preview for the grid. */
    public static String preview(byte[] data, int maxLen) {
        if (data == null) return "∅";
        String s;
        if (isConfluent(data)) {
            s = "[schema-registry id=" + schemaId(data) + "] " + hex(data);
        } else if (isValidUtf8(data)) {
            s = text(data).replace('\n', ' ').replace('\r', ' ');
        } else {
            s = "0x" + hex(data);
        }
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private static String auto(byte[] data) {
        if (isConfluent(data)) {
            return "// Confluent Schema Registry, schema id = " + schemaId(data)
                    + " (Avro/Protobuf декодирование не подключено)\n" + hex(data);
        }
        if (!isValidUtf8(data)) return hex(data);
        String s = text(data);
        String t = s.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            String pretty = prettyJson(s);
            return pretty != null ? pretty : s;
        }
        return s;
    }

    /** Pretty-prints JSON; if it is not JSON, returns the original text. */
    public static String prettyJson(String s) {
        if (s == null) return null;
        try {
            JsonNode node = MAPPER.readTree(s);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return s;
        }
    }

    public static boolean isJson(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            MAPPER.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    /** First byte 0 followed by a 4-byte schema id — such a message cannot be text. */
    private static boolean isConfluent(byte[] data) {
        return data.length > 5 && data[0] == 0;
    }

    private static int schemaId(byte[] data) {
        return ByteBuffer.wrap(data, 1, 4).getInt();
    }
}
