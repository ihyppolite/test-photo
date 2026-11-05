package fr.example.nist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** CLI exits 0 on success, 2 on invalid input or I/O failure. Never overwrites output. */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage()); System.exit(2);
        }
    }
    static void run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println("""
                ANSI/NIST-ITL 1-2011 Update 2015 - bounded FACE subset (types 1, 2, 10)
                convert  input.png metadata.properties output.an2
                validate input.an2 [original.png]
                extract  input.an2 output.png
                validate checks this subset only; original.png checks byte equality (preserve mode).
                No files are overwritten. No biometric quality or receiving-profile certification.
                """); return;
        }
        switch (args[0]) {
            case "convert" -> {
                Config.require(args.length == 4, "convert requires 3 arguments");
                byte[] input = readBounded(Path.of(args[1]), Png.MAX_BYTES);
                byte[] output = NistConverter.convert(input, Config.load(Path.of(args[2])));
                writeNew(Path.of(args[3]), output);
                System.out.println("Created " + output.length + " bytes; types 1, 2, 10; VER=0502; SAP=0");
            }
            case "validate", "extract" -> {
                boolean extract = args[0].equals("extract");
                Config.require(extract ? args.length == 3 : args.length == 2 || args.length == 3, "Invalid argument count");
                byte[] input = readBounded(Path.of(args[1]), NistValidator.MAX_TRANSACTION_BYTES);
                NistValidator.Result result = NistValidator.validate(input);
                if (extract) writeNew(Path.of(args[2]), result.image());
                else {
                    if (args.length == 3) Config.require(Arrays.equals(result.image(), readBounded(Path.of(args[2]),Png.MAX_BYTES)),
                        "Embedded image differs from original PNG bytes");
                    for (var record : result.records()) System.out.printf("Type-%d offset=%d LEN=%d%n",record.type(),record.offset(),record.length());
                    System.out.printf("PASS (implemented subset): %dx%d, PNG, %s, SAP=0%n",result.width(),result.height(),result.colorSpace());
                    System.out.println("Image SHA-256: " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.image())));
                    if (args.length == 3) System.out.println("Original PNG: byte-for-byte identical");
                }
            }
            default -> throw new IllegalArgumentException("Unknown command; use help");
        }
    }

    private static byte[] readBounded(Path path, int max) throws IOException {
        // Bounded read also protects against a file growing between stat and read.
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(max + 1);
            Config.require(bytes.length <= max, "Input too large"); return bytes;
        }
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException {
        // CREATE_NEW is exclusive: an existing path, including the input path, is never replaced.
        try (var out = Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            out.write(bytes);
        }
    }
}
