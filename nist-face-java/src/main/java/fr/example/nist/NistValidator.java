package fr.example.nist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent reader of the emitted subset, NOT a universal ANSI/NIST certification engine. */
public final class NistValidator {
    public static final int MAX_TRANSACTION_BYTES = Png.MAX_BYTES + 2 * 1024 * 1024;

    public record Record(int type, int offset, int length, Map<Integer, String> fields, byte[] image) {
        public Record {
            fields = Map.copyOf(fields);
            image = image == null ? null : image.clone();
        }
        @Override public byte[] image() { return image == null ? null : image.clone(); }
    }
    public record Result(List<Record> records, int width, int height, String colorSpace, byte[] image) {
        public Result { records = List.copyOf(records); image = image.clone(); }
        @Override public byte[] image() { return image.clone(); }
    }
    private NistValidator() {}

    public static Result validate(byte[] data) throws IOException {
        Config.require(data.length > 0 && data.length <= MAX_TRANSACTION_BYTES, "Transaction size outside bounds");
        List<Record> records = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            Config.require(records.size() < 3, "Exactly three records expected");
            Record record = readRecord(data, offset);
            records.add(record); offset += record.length();
        }
        Config.require(records.size() == 3, "Types 1, 2 and 10 required");
        Record one = records.get(0), two = records.get(1), ten = records.get(2);
        Config.require(one.type() == 1 && two.type() == 2 && ten.type() == 10, "Required record order: 1, 2, 10");
        exactFields(one, Set.of(1,2,3,4,5,7,8,9,11,12,13));
        Config.require(two.fields().containsKey(1) && two.fields().containsKey(2), "Missing Type-2 header/IDC");
        exactFields(ten, Set.of(1,2,3,4,5,6,7,8,9,10,11,12,13,992,999));
        equalsField(one, 2, "0502");
        String[] entries = field(one, 3).split("\u001e", -1);
        Config.require(entries.length == 3, "CNT must contain three subfields");
        int[][] expected = {{1,2},{2,0},{10,1}};
        for (int i = 0; i < entries.length; i++) {
            String[] items = entries[i].split("\u001f", -1);
            Config.require(items.length == 2, "Invalid CNT item count");
            Config.require(Config.number(items[0], 1, 99, "CNT type") == expected[i][0]
                && Config.number(items[1], 0, 99, "CNT count/IDC") == expected[i][1], "CNT mismatch");
        }
        Config.require(field(one,4).matches("[A-Za-z]{1,16}"), "Invalid TOT");
        Config.date(field(one,5));
        for (int f : new int[]{7,8,9}) Config.ascii(field(one,f), "1." + f, false);
        equalsField(one,11,"00.00"); equalsField(one,12,"00.00");
        String[] domain = field(one,13).split("\u001f", -1);
        Config.require(domain.length == 1 || domain.length == 2, "Invalid DOM");
        for (String d : domain) Config.ascii(d, "DOM", false);
        Config.require(Config.number(field(two,2), 0, 99, "2.002") == 0, "Expected Type-2 IDC 0");
        Config.require(Config.number(field(ten,2), 0, 99, "10.002") == 1, "Expected Type-10 IDC 1");
        Config.require(Config.number(field(ten,992), 0, 99, "10.992") == 0, "Broken Type-2 cross-reference");
        Config.require(field(two,2).length() <= 2, "Type-2 IDC exceeds 2 digits");
        for (int f : new int[]{2,992}) Config.require(field(ten,f).length() <= 2, "IDC exceeds 2 digits");
        for (int f : new int[]{6,7,9,10}) Config.require(field(ten,f).length() <= 5, "Image numeric field exceeds 5 digits");
        Config.require(field(ten,8).length() == 1, "SLC must have one digit");
        equalsField(ten,3,"FACE"); Config.ascii(field(ten,4), "SRC", false); Config.date(field(ten,5));
        int width = Config.number(field(ten,6), 10, 99999, "HLL");
        int height = Config.number(field(ten,7), 10, 99999, "VLL");
        int slc = Config.number(field(ten,8), 0, 2, "SLC");
        int x = Config.number(field(ten,9), 1, 99999, "THPS"), y = Config.number(field(ten,10),1,99999,"TVPS");
        Config.require(slc == 0 || x == y, "Unequal physical scales");
        equalsField(ten,11,"PNG"); equalsField(ten,13,"0");
        Png.Info info = Png.inspect(ten.image(), true);
        Config.require(info.width() == width && info.height() == height, "Image dimensions disagree with Type-10");
        equalsField(ten,12,info.colorSpace());
        return new Result(records, width, height, info.colorSpace(), ten.image());
    }

    private static Record readRecord(byte[] data, int start) {
        int firstEnd = delimiter(data, start, data.length);
        Config.require(firstEnd < data.length && data[firstEnd] == 29, "Missing GS after LEN");
        String first = text(data, start, firstEnd);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]{1,2})\\.([0-9]{1,9}):([0-9]{1,9})").matcher(first);
        Config.require(m.matches(), "Invalid LEN header");
        int type = Integer.parseInt(m.group(1));
        Config.require(Set.of(1,2,10).contains(type), "Unsupported record type");
        Config.require(Integer.parseInt(m.group(2)) == 1, "First field must be LEN");
        int len = Config.number(m.group(3), 1, MAX_TRANSACTION_BYTES, "LEN");
        Config.require(type != 10 || m.group(3).length() <= 8, "Type-10 LEN exceeds 8 digits");
        Config.require(len <= data.length - start && len > firstEnd - start + 1, "Record length outside file");
        int end = start + len;
        Config.require(data[end - 1] == 28, "Missing FS at declared record end");
        Map<Integer, String> fields = new LinkedHashMap<>();
        fields.put(1, m.group(3));
        int p = firstEnd + 1;
        byte[] image = null;
        while (p < end - 1) {
            int colon = p;
            while (colon < end - 1 && data[colon] != ':') {
                Config.require(data[colon] != 28 && data[colon] != 29 && colon - p < 13, "Invalid field tag");
                colon++;
            }
            Config.require(colon < end - 1, "Missing field colon");
            String tag = text(data,p,colon);
            var tagMatch = java.util.regex.Pattern.compile("([0-9]{1,2})\\.([0-9]{1,9})").matcher(tag);
            Config.require(tagMatch.matches() && Integer.parseInt(tagMatch.group(1)) == type, "Field has incorrect record type");
            int field = Integer.parseInt(tagMatch.group(2));
            Config.require(field > 1 && !fields.containsKey(field), "Duplicate/invalid field number");
            if (type == 10 && field == 999) {
                // Binary can contain FS/GS/RS/US. ONLY LEN defines its end.
                Config.require(colon + 1 < end - 1, "Empty image");
                image = Arrays.copyOfRange(data, colon + 1, end - 1);
                fields.put(field, "<binary>"); p = end - 1;
            } else {
                int separator = delimiter(data, colon + 1, end);
                Config.require(separator < end, "Unterminated field");
                String value = text(data,colon + 1,separator);
                Config.ascii(value,tag,true);
                fields.put(field,value);
                Config.require(fields.size() <= 1024, "Too many fields");
                if (data[separator] == 28) {
                    Config.require(separator == end - 1, "FS before declared record end");
                    p = separator;
                } else {
                    Config.require(separator < end - 2, "Trailing GS");
                    p = separator + 1;
                }
            }
        }
        return new Record(type,start,len,fields,image);
    }

    private static int delimiter(byte[] data, int p, int end) {
        int begin = p;
        while (p < end && data[p] != 28 && data[p] != 29) {
            Config.require(p - begin <= 65560, "Text field exceeds limit"); p++;
        }
        return p;
    }
    private static String text(byte[] data, int from, int to) {
        for (int i = from; i < to; i++) Config.require((data[i] & 128) == 0, "Non-ASCII text byte");
        return new String(data,from,to-from,StandardCharsets.US_ASCII);
    }
    private static String field(Record r, int f) {
        String s = r.fields().get(f); Config.require(s != null, "Missing " + r.type() + "." + f); return s;
    }
    private static void equalsField(Record r, int f, String expected) {
        Config.require(field(r,f).equals(expected), "Unexpected value of " + r.type() + "." + f);
    }
    private static void exactFields(Record r, Set<Integer> expected) {
        Config.require(r.fields().keySet().equals(expected), "Missing/unsupported fields in Type-" + r.type());
    }
}
