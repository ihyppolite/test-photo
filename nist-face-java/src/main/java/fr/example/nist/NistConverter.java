package fr.example.nist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Traditional encoding: exactly one Type-1, one Type-2 and one Type-10 FACE. */
public final class NistConverter {
    private NistConverter() {}

    public static byte[] convert(byte[] sourcePng, Config config) throws IOException {
        Config.require(sourcePng != null && sourcePng.length <= Png.MAX_BYTES, "PNG input missing or too large");
        Config.require(config != null, "Missing configuration");
        byte[] png = "normalize-rgb8".equals(config.get("image.mode"))
            ? Png.normalize(sourcePng) : sourcePng.clone();
        Png.Info info = Png.inspect(png, true);
        Map<Integer, String> one = new TreeMap<>();
        one.put(2, "0502");
        // CRC is the number of FOLLOWING records (2), not total records (3).
        one.put(3, "1\u001f2\u001e2\u001f00\u001e10\u001f01");
        one.put(4, config.get("transaction.type"));
        one.put(5, config.get("transaction.date"));
        one.put(7, config.get("destination")); one.put(8, config.get("origin"));
        one.put(9, config.get("transaction.control"));
        one.put(11, "00.00"); one.put(12, "00.00");
        one.put(13, config.get("domain.name") + (config.get("domain.version") == null
            ? "" : "\u001f" + config.get("domain.version")));
        Map<Integer, String> two = new TreeMap<>(config.type2);
        two.put(2, "00");
        Map<Integer, String> ten = new TreeMap<>();
        ten.put(2, "01"); ten.put(3, "FACE"); ten.put(4, config.get("capture.source"));
        ten.put(5, config.get("capture.date")); ten.put(6, Integer.toString(info.width()));
        ten.put(7, Integer.toString(info.height())); ten.put(8, config.get("scale.units"));
        ten.put(9, config.get("scale.x")); ten.put(10, config.get("scale.y"));
        ten.put(11, "PNG"); ten.put(12, info.colorSpace());
        ten.put(13, "0"); // Unknown acquisition profile: does NOT assert mugshot quality.
        ten.put(992, "00"); // Biographical cross-reference to Type-2, not a reused image IDC.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(record(1, one, null)); out.writeBytes(record(2, two, null));
        out.writeBytes(record(10, ten, png));
        byte[] transaction = out.toByteArray();
        NistValidator.validate(transaction); // Never return an internally inconsistent file.
        return transaction;
    }

    private static byte[] record(int type, Map<Integer, String> fields, byte[] binary) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        fields.forEach((field, value) -> {
            tail.write(29); tail.writeBytes(ascii(tag(type, field) + value));
        });
        if (binary != null) {
            tail.write(29); tail.writeBytes(ascii(tag(type, 999))); tail.writeBytes(binary);
        }
        tail.write(28);
        byte[] bytes = tail.toByteArray();
        String prefix = tag(type, 1);
        int length = prefix.length() + 1 + bytes.length;
        for (;;) {
            int next = prefix.length() + Integer.toString(length).length() + bytes.length;
            if (next == length) break;
            length = next;
        }
        Config.require(type != 10 || length <= 99999999, "Type-10 LEN exceeds 8 digits");
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        out.writeBytes(ascii(prefix + length)); out.writeBytes(bytes);
        Config.require(out.size() == length, "Internal record length error");
        return out.toByteArray();
    }

    private static String tag(int type, int field) {
        return type + "." + String.format(java.util.Locale.ROOT, "%03d", field) + ":";
    }
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
}
