package fr.example.nist;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Bounded PNG inspection, CRC checks and full decoding before accepting an image. */
final class Png {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    static final long MAX_PIXELS = 20_000_000;
    static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    record Info(int width, int height, int depth, int colorType, String colorSpace,
                boolean transparency, BufferedImage image) {}

    private Png() {}

    static Info inspect(byte[] data, boolean normalizedSubset) throws IOException {
        Config.require(data.length >= 45 && data.length <= MAX_BYTES, "PNG size outside supported bounds");
        Config.require(Arrays.equals(Arrays.copyOf(data, 8), SIGNATURE), "Input is not a PNG");
        int p = 8, w = 0, h = 0, depth = 0, ct = -1;
        boolean header = false, idat = false, idatEnded = false, end = false, transparency = false, srgb = false;
        Set<String> singleton = new java.util.HashSet<>();
        while (p < data.length) {
            Config.require(data.length - p >= 12, "Truncated PNG chunk");
            long count = unsigned(data, p);
            Config.require(count <= data.length - p - 12L, "PNG chunk length exceeds file");
            int n = (int) count;
            String name = new String(data, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            Config.require(name.matches("[A-Za-z]{4}") && Character.isUpperCase(name.charAt(2)), "Invalid PNG chunk name");
            Config.require(header || name.equals("IHDR"), "IHDR must be first");
            CRC32 crc = new CRC32(); crc.update(data, p + 4, n + 4);
            Config.require(crc.getValue() == unsigned(data, p + 8 + n), "PNG CRC mismatch in " + name);
            if (Set.of("IHDR", "PLTE", "IEND", "tRNS", "sRGB", "iCCP", "pHYs").contains(name)) {
                Config.require(singleton.add(name), "Duplicate PNG chunk: " + name);
            }
            Config.require(!Set.of("acTL", "fcTL", "fdAT").contains(name), "Animated PNG is not supported");
            if (Character.isUpperCase(name.charAt(0))) {
                Config.require(Set.of("IHDR", "PLTE", "IDAT", "IEND").contains(name), "Unknown critical PNG chunk");
            }
            if (name.equals("IHDR")) {
                Config.require(!header && n == 13, "Invalid IHDR"); header = true;
                long ww = unsigned(data, p + 8), hh = unsigned(data, p + 12);
                Config.require(ww >= 10 && ww <= 99999 && hh >= 10 && hh <= 99999,
                    "Type-10 dimensions must be 10..99999");
                Config.require(ww * hh <= MAX_PIXELS, "PNG exceeds pixel limit");
                w = (int)ww; h = (int)hh; depth = data[p + 16] & 255; ct = data[p + 17] & 255;
                boolean validDepth = switch(ct) {
                    case 0 -> Set.of(1,2,4,8,16).contains(depth);
                    case 2,4,6 -> depth == 8 || depth == 16;
                    case 3 -> Set.of(1,2,4,8).contains(depth);
                    default -> false;
                };
                Config.require(validDepth && data[p + 18] == 0 && data[p + 19] == 0
                    && (data[p + 20] == 0 || data[p + 20] == 1), "Unsupported PNG header");
            } else if (name.equals("IDAT")) {
                Config.require(!idatEnded, "Non-consecutive IDAT chunks");
                Config.require(ct != 3 || singleton.contains("PLTE"), "Indexed PNG without PLTE");
                idat = true;
            } else {
                if (idat) idatEnded = true;
                if (name.equals("PLTE")) {
                    Config.require(!idat && n > 0 && n <= 768 && n % 3 == 0 && ct != 0 && ct != 4, "Invalid PLTE");
                } else if (name.equals("tRNS")) {
                    Config.require(!idat && (ct == 0 || ct == 2 || ct == 3), "Invalid tRNS"); transparency = true;
                } else if (name.equals("sRGB")) {
                    Config.require(!idat && n == 1 && (data[p + 8] & 255) <= 3, "Invalid sRGB chunk"); srgb = true;
                } else if (name.equals("IEND")) {
                    Config.require(n == 0 && idat && p + 12 == data.length, "Invalid PNG ending"); end = true;
                }
            }
            p += n + 12;
        }
        Config.require(end, "Missing IEND");
        if (normalizedSubset) {
            Config.require((ct == 0 || ct == 2) && (depth == 8 || depth == 16) && !transparency,
                "Preserve mode requires RGB/gray PNG, 8/16 bits, without transparency. Use image.mode=normalize-rgb8 for opaque inputs.");
        }
        BufferedImage image;
        try (var in = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReadersByFormatName("PNG");
            Config.require(readers.hasNext(), "No PNG ImageIO reader available");
            var reader = readers.next();
            try { reader.setInput(in, true, false); image = reader.read(0); }
            finally { reader.dispose(); }
        }
        Config.require(image != null && image.getWidth() == w && image.getHeight() == h, "PNG decode/dimensions mismatch");
        return new Info(w, h, depth, ct, ct == 0 ? "GRAY" : srgb ? "SRGB" : "RGB",
            transparency || ct == 4 || ct == 6, image);
    }

    static byte[] normalize(byte[] source) throws IOException {
        Info info = inspect(source, false);
        BufferedImage output = new BufferedImage(info.width(), info.height(), BufferedImage.TYPE_INT_RGB);
        var alpha = info.image().getAlphaRaster();
        for (int y = 0; y < info.height(); y++) {
            for (int x = 0; x < info.width(); x++) {
                // getRGB returns 8-bit components in default sRGB. No hidden background compositing.
                int pixel = info.image().getRGB(x, y);
                Config.require((pixel >>> 24) == 255, "Transparent pixels: provide an opaque photograph");
                if (alpha != null) {
                    int bits = alpha.getSampleModel().getSampleSize(0);
                    Config.require(alpha.getSample(x,y,0) == (1L << bits) - 1,
                        "Transparent pixels: provide an opaque photograph");
                }
                output.setRGB(x, y, pixel);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Config.require(ImageIO.write(output, "PNG", out), "No PNG writer available");
        byte[] result = out.toByteArray(); inspect(result, true); return result;
    }

    private static long unsigned(byte[] b, int p) {
        return ((b[p] & 255L) << 24) | ((b[p+1] & 255L) << 16) | ((b[p+2] & 255L) << 8) | (b[p+3] & 255L);
    }
}
