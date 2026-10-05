package ru.ops.console.config;

import java.util.List;
import java.util.regex.Pattern;

/** Matches names against masks like "orch.*", "payments.*.dlq", "*". Case-insensitive. */
public final class Globs {

    private Globs() {
    }

    public static boolean matchesAny(String value, List<String> patterns) {
        if (patterns == null || value == null) return false;
        for (String p : patterns) {
            if (p != null && matches(value, p.trim())) return true;
        }
        return false;
    }

    public static boolean matches(String value, String glob) {
        if ("*".equals(glob)) return true;
        StringBuilder re = new StringBuilder("(?i)^");
        for (String part : glob.split("\\*", -1)) {
            re.append(Pattern.quote(part)).append(".*");
        }
        re.setLength(re.length() - 2);
        re.append('$');
        return Pattern.compile(re.toString()).matcher(value).matches();
    }
}
