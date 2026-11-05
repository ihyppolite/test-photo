package fr.example.nist;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Metadata supplied by the caller; no invented capture date or agency identifiers. */
public final class Config {
    private static final Set<String> KEYS = Set.of("transaction.type", "transaction.date",
        "destination", "origin", "transaction.control", "domain.name", "domain.version",
        "capture.source", "capture.date", "scale.units", "scale.x", "scale.y", "image.mode");
    final Map<String, String> values;
    final Map<Integer, String> type2;

    private Config(Map<String, String> values, Map<Integer, String> type2) {
        this.values = Map.copyOf(values);
        this.type2 = Map.copyOf(type2);
    }

    public static Config load(Path path) throws IOException {
        Properties p = new Properties() {
            private static final long serialVersionUID = 1L;
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate property: " + key);
                return super.put(key, value);
            }
        };
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(in); }
        return from(p);
    }

    public static Config from(Properties p) {
        Map<String, String> values = new TreeMap<>();
        Map<Integer, String> t2 = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            String value = p.getProperty(key);
            if (key.startsWith("type2.")) {
                String suffix = key.substring(6);
                require(suffix.matches("[0-9]{1,9}"), "Invalid Type-2 field: " + key);
                int field = Integer.parseInt(suffix);
                require(field >= 3, "2.001 and 2.002 are managed by the converter");
                ascii(value, key, true);
                require(t2.putIfAbsent(field, value) == null, "Duplicate Type-2 field: " + key);
            } else {
                require(KEYS.contains(key), "Unknown property: " + key);
                values.put(key, value);
            }
        }
        for (String key : KEYS) {
            if (!key.equals("image.mode") && !key.equals("domain.version")) {
                require(values.containsKey(key), "Missing property: " + key);
                ascii(values.get(key), key, false);
            }
        }
        if (values.containsKey("domain.version")) ascii(values.get("domain.version"), "domain.version", false);
        require(values.get("transaction.type").matches("[A-Za-z]{1,16}"), "TOT must be 1-16 ASCII letters");
        date(values.get("transaction.date"));
        date(values.get("capture.date"));
        int units = number(values.get("scale.units"), 0, 2, "scale.units");
        int x = number(values.get("scale.x"), 1, 99999, "scale.x");
        int y = number(values.get("scale.y"), 1, 99999, "scale.y");
        require(units == 0 || x == y, "THPS must equal TVPS when SLC is 1 or 2");
        values.put("scale.units", Integer.toString(units));
        values.put("scale.x", Integer.toString(x));
        values.put("scale.y", Integer.toString(y));
        require(t2.size() <= 1000, "At most 1000 Type-2 user fields supported");
        require(t2.values().stream().mapToLong(String::length).sum() <= 1_000_000,
            "Type-2 text exceeds implementation limit");
        String mode = values.getOrDefault("image.mode", "preserve");
        require(Set.of("preserve", "normalize-rgb8").contains(mode), "Unsupported image.mode");
        values.put("image.mode", mode);
        return new Config(values, t2);
    }

    String get(String key) { return values.get(key); }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static void ascii(String value, String label, boolean structured) {
        require(value != null && !value.isEmpty(), "Empty " + label);
        require(value.length() <= 65536, "Text too long: " + label);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require((c >= 32 && c <= 126) || (structured && (c == 30 || c == 31)),
                "Non-ASCII or forbidden control character in " + label);
        }
    }

    static int number(String s, int min, int max, String label) {
        require(s != null && s.matches("[0-9]{1,9}"), "Invalid integer: " + label);
        int n = Integer.parseInt(s);
        require(n >= min && n <= max, "Out of range: " + label);
        return n;
    }

    static void date(String s) {
        require(s != null && s.matches("[0-9]{8}") && !s.startsWith("0000"), "Date must be yyyyMMdd, year >= 0001");
        try {
            LocalDate.parse(s, DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("Invalid calendar date: " + s, e); }
    }
}
