package fr.example.nist;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Dependency-free executable regression suite. Failing assertions exit nonzero. */
public final class TestSuite {
    private static int passed;
    @FunctionalInterface interface Test { void run() throws Exception; }
    private TestSuite() {}
    public static void main(String[] args) throws Exception {
        byte[] png = image(32,40,BufferedImage.TYPE_INT_RGB);
        byte[] valid = NistConverter.convert(png,Config.from(properties()));
        test("RGB round trip preserves every PNG byte", () -> {
            var r = NistValidator.validate(valid);
            check(r.width() == 32 && r.height() == 40, "dimensions");
            check(Arrays.equals(png,r.image()),"PNG equality");
            check(r.records().stream().map(NistValidator.Record::type).toList().equals(java.util.List.of(1,2,10)),"types");
        });
        test("All LENs include tags and terminal FS", () -> {
            var r = NistValidator.validate(valid); int p = 0;
            for (var rec : r.records()) {
                check(rec.offset() == p, "offset"); p += rec.length(); check(valid[p-1] == 28,"FS");
                check(Integer.parseInt(rec.fields().get(1)) == rec.length(),"LEN");
            }
            check(p == valid.length,"total");
        });
        test("VER, CNT, NSR/NTR and cross-reference", () -> {
            var r = NistValidator.validate(valid);
            var f = r.records().get(0).fields();
            check(f.get(2).equals("0502"),"version");
            check(f.get(3).equals("1\u001f2\u001e2\u001f00\u001e10\u001f01"),"CNT count excludes Type-1");
            check(f.get(11).equals("00.00") && f.get(12).equals("00.00"),"no fingerprint resolution");
            check(r.records().get(2).fields().get(992).equals("00"),"T2C");
        });
        test("PNG containing all NIST separators and fake tags", () -> {
            byte[] binary = addChunk(png,"npSt",new byte[]{28,29,30,31,'1','0','.', '9','9','9',':',28});
            var r = NistValidator.validate(NistConverter.convert(binary,Config.from(properties())));
            check(Arrays.equals(binary,r.image()),"binary cannot be delimiter-split");
        });
        test("Grayscale 8-bit", () -> {
            var r = NistValidator.validate(NistConverter.convert(image(32,40,BufferedImage.TYPE_BYTE_GRAY),Config.from(properties())));
            check(r.colorSpace().equals("GRAY"),"gray CSP");
        });
        test("Grayscale 16-bit preserved", () -> {
            byte[] gray = image(32,40,BufferedImage.TYPE_USHORT_GRAY);
            check(Arrays.equals(gray,NistValidator.validate(NistConverter.convert(gray,Config.from(properties()))).image()),"16 bit preservation");
        });
        test("sRGB chunk reported as SRGB", () -> {
            byte[] tagged = addChunk(png,"sRGB",new byte[]{0});
            check(NistValidator.validate(NistConverter.convert(tagged,Config.from(properties()))).colorSpace().equals("SRGB"),"sRGB");
        });
        test("Type-2 structured items and subfields", () -> {
            Properties p = properties(); p.setProperty("type2.123", "ONE\u001fTWO\u001eTHREE");
            check(NistValidator.validate(NistConverter.convert(png,Config.from(p))).records().get(1).fields().get(123)
                .equals("ONE\u001fTWO\u001eTHREE"),"structure");
        });
        test("Type-2 high field number and no implicit demographic schema", () -> {
            Properties p = properties(); p.setProperty("type2.123456789", "CUSTOM");
            check(NistValidator.validate(NistConverter.convert(png,Config.from(p))).records().get(1).fields().containsKey(123456789),"large tag");
        });
        test("Minimal Type-2 header", () -> {
            Properties p = properties(); p.remove("type2.003");
            check(NistValidator.validate(NistConverter.convert(png,Config.from(p))).records().get(1).fields().size() == 2,"minimal");
        });
        test("LEN fixed point over decimal boundaries", () -> {
            for (int n : new int[]{1,9,80,90,98,99,100,970,980,990,998,999,1000,9970,9980,9990,9999,10000,60000}) {
                Properties p = properties(); p.setProperty("type2.003","X".repeat(n));
                check(NistValidator.validate(NistConverter.convert(png,Config.from(p))).records().get(1).fields().get(3).length() == n,"boundary " + n);
            }
        });
        test("Deterministic conversion", () -> check(Arrays.equals(valid,NistConverter.convert(png,Config.from(properties()))),"determinism"));
        test("Defensive copies in validation result", () -> {
            var r = NistValidator.validate(valid); byte[] copy = r.image(); copy[0] = 0;
            check(r.image()[0] == (byte)137,"copy");
        });
        test("Optional domain version", () -> {
            Properties p = properties(); p.remove("domain.version");
            NistValidator.validate(NistConverter.convert(png,Config.from(p)));
        });
        test("Non-square pixel ratio", () -> {
            Properties p = properties(); p.setProperty("scale.x","4"); p.setProperty("scale.y","3");
            check(NistValidator.validate(NistConverter.convert(png,Config.from(p))).records().get(2).fields().get(9).equals("4"),"aspect");
        });
        test("Equal physical scales", () -> {
            Properties p = properties(); p.setProperty("scale.units","1");p.setProperty("scale.x","300");p.setProperty("scale.y","300");
            NistValidator.validate(NistConverter.convert(png,Config.from(p)));
        });
        test("Reject PNG CRC corruption", () -> { byte[] b=png.clone(); b[20]^=1; invalid(()->NistConverter.convert(b,Config.from(properties()))); });
        test("Reject non-PNG source", () -> invalid(()->NistConverter.convert("not a png".getBytes(StandardCharsets.US_ASCII),Config.from(properties()))));
        test("Reject truncated PNG", () -> invalid(()->NistConverter.convert(Arrays.copyOf(png,png.length-1),Config.from(properties()))));
        test("Reject trailing PNG bytes", () -> invalid(()->NistConverter.convert(Arrays.copyOf(png,png.length+1),Config.from(properties()))));
        test("Reject PNG size bomb before raster allocation", () -> {
            byte[] b=png.clone(); putInt(b,16,99999); putInt(b,20,99999); crc(b,12,17,29);
            invalid(()->NistConverter.convert(b,Config.from(properties())));
        });
        test("Reject unsupported dimensions", () -> invalid(()->NistConverter.convert(image(9,40,BufferedImage.TYPE_INT_RGB),Config.from(properties()))));
        test("Reject animated PNG", () -> invalid(()->NistConverter.convert(addChunk(png,"acTL",new byte[8]),Config.from(properties()))));
        test("Reject unknown critical chunk", () -> invalid(()->NistConverter.convert(addChunk(png,"ABCD",new byte[0]),Config.from(properties()))));
        test("Reject palette in preserve mode", () -> invalid(()->NistConverter.convert(image(32,40,BufferedImage.TYPE_BYTE_INDEXED),Config.from(properties()))));
        test("Explicit palette normalization", () -> {
            Properties p=properties();p.setProperty("image.mode","normalize-rgb8");
            var r=NistValidator.validate(NistConverter.convert(image(32,40,BufferedImage.TYPE_BYTE_INDEXED),Config.from(p)));
            check(r.width()==32,"normalized width");
        });
        test("Opaque alpha normalization", () -> {
            Properties p=properties();p.setProperty("image.mode","normalize-rgb8");
            NistValidator.validate(NistConverter.convert(image(32,40,BufferedImage.TYPE_INT_ARGB),Config.from(p)));
        });
        test("Reject actual transparency even in normalize mode", () -> {
            BufferedImage transparent=new BufferedImage(32,40,BufferedImage.TYPE_INT_ARGB);
            Properties p=properties();p.setProperty("image.mode","normalize-rgb8");
            invalid(()->NistConverter.convert(encode(transparent),Config.from(p)));
        });
        test("RGB 16-bit preserved without quantization", () -> {
            byte[] rgb16=encode(image16(false));
            check((rgb16[24]&255)==16,"fixture bit depth");
            check(Arrays.equals(rgb16,NistValidator.validate(NistConverter.convert(rgb16,Config.from(properties()))).image()),"RGB16 equality");
        });
        test("Reject subtle 16-bit alpha rounded to 255 by getRGB", () -> {
            BufferedImage im=image16(true);im.getAlphaRaster().setSample(0,0,0,65534);
            check((im.getRGB(0,0)>>>24)==255,"fixture must exercise rounding");
            Properties p=properties();p.setProperty("image.mode","normalize-rgb8");
            invalid(()->NistConverter.convert(encode(im),Config.from(p)));
        });
        test("Reject non-ASCII rather than replacing characters", () -> badProperty("type2.003","Andr\u00e9"));
        test("Reject GS injection", () -> badProperty("type2.003","A\u001d2.009:INJECT"));
        test("Reject FS injection", () -> badProperty("origin","A\u001c"));
        test("Reject US injection into scalar metadata", () -> badProperty("origin","A\u001fB"));
        test("Reject reserved Type-2 field", () -> badProperty("type2.001","123"));
        test("Reject aliases for same Type-2 field", () -> badProperty("type2.3","DUPLICATE"));
        test("Reject unknown properties", () -> badProperty("typo","oops"));
        test("Reject invalid date", () -> badProperty("capture.date","20260230"));
        test("Accept leap date", () -> { Properties p=properties();p.setProperty("capture.date","20240229");Config.from(p); });
        test("Reject invalid TOT", () -> badProperty("transaction.type","TEST123"));
        test("Reject unequal physical scales", () -> {
            Properties p=properties();p.setProperty("scale.units","1");p.setProperty("scale.y","2");invalid(()->Config.from(p));
        });
        test("Reject missing mandatory metadata", () -> {
            Properties p=properties();p.remove("capture.date");invalid(()->Config.from(p));
        });
        test("Reject duplicate property lines", () -> {
            Path p=Files.createTempFile("nist-duplicate-", ".properties");
            try { Files.writeString(p,"origin=A\norigin=B\n");invalid(()->Config.load(p)); } finally { Files.delete(p); }
        });
        test("Reject truncated transaction", () -> invalid(()->NistValidator.validate(Arrays.copyOf(valid,valid.length-1))));
        test("Reject bytes after transaction", () -> invalid(()->NistValidator.validate(Arrays.copyOf(valid,valid.length+1))));
        test("Reject wrong version", () -> invalid(()->NistValidator.validate(replace(valid,"1.002:0502","1.002:0501"))));
        test("Reject CNT total-record off-by-one", () -> invalid(()->NistValidator.validate(replace(valid,"1.003:1\u001f2","1.003:1\u001f3"))));
        test("Reject image IDC mismatch", () -> invalid(()->NistValidator.validate(replace(valid,"10.002:01","10.002:02"))));
        test("Reject broken Type-2 cross-reference", () -> invalid(()->NistValidator.validate(replace(valid,"10.992:00","10.992:02"))));
        test("Reject wrong image dimensions", () -> invalid(()->NistValidator.validate(replace(valid,"10.006:32","10.006:33"))));
        test("Reject wrong CGA", () -> invalid(()->NistValidator.validate(replace(valid,"10.011:PNG","10.011:BAD"))));
        test("Reject incorrect CSP", () -> invalid(()->NistValidator.validate(replace(valid,"10.012:RGB","10.012:YCC"))));
        test("Reject unverified SAP upgrade", () -> invalid(()->NistValidator.validate(replace(valid,"10.013:0","10.013:1"))));
        test("Reject duplicate field number", () -> invalid(()->NistValidator.validate(replace(valid,"1.012:00.00","1.011:00.00"))));
        test("Reject missing mandatory field", () -> invalid(()->NistValidator.validate(replace(valid,"10.013:0","10.014:0"))));
        test("Reject malformed field tag", () -> invalid(()->NistValidator.validate(replace(valid,"10.006:32","11.006:32"))));
        test("Reject invalid embedded PNG", () -> {
            byte[] b=valid.clone(); int pos=index(b,"10.999:".getBytes(StandardCharsets.US_ASCII)); b[pos+7]=0;
            invalid(()->NistValidator.validate(b));
        });
        test("Reject length off by one", () -> {
            byte[] b=valid.clone();int colon=index(b,new byte[]{':'});int stop=colon+1;while(b[stop]!=29)stop++;
            String n=new String(b,colon+1,stop-colon-1,StandardCharsets.US_ASCII);
            byte[] changed=Integer.toString(Integer.parseInt(n)-1).getBytes(StandardCharsets.US_ASCII);
            check(changed.length==n.length(),"test setup");System.arraycopy(changed,0,b,colon+1,changed.length);
            invalid(()->NistValidator.validate(b));
        });
        test("CLI conversion, source comparison, extraction, no overwrite", () -> {
            Path dir=Files.createTempDirectory("nist-cli-");
            try {
                Path in=dir.resolve("face.png"),cfg=dir.resolve("meta.properties"),out=dir.resolve("face.an2"),ext=dir.resolve("extract.png");
                Files.write(in,png);
                try(var writer=Files.newBufferedWriter(cfg)) { properties().store(writer,"Synthetic test"); }
                Main.run(new String[]{"convert",in.toString(),cfg.toString(),out.toString()});
                Main.run(new String[]{"validate",out.toString(),in.toString()});
                Main.run(new String[]{"extract",out.toString(),ext.toString()});
                check(Arrays.equals(png,Files.readAllBytes(ext)),"CLI extract");
                invalid(()->Main.run(new String[]{"convert",in.toString(),cfg.toString(),out.toString()}));
                check(Arrays.equals(valid,Files.readAllBytes(out)),"unchanged output");
                Files.write(in,image(32,40,BufferedImage.TYPE_BYTE_GRAY));
                invalid(()->Main.run(new String[]{"validate",out.toString(),in.toString()}));
            } finally { try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p); } }
        });
        // Independent Python-created vector, not produced by NistConverter.
        test("Read independently encoded reference fixture", () -> {
            byte[] fixture=Files.readAllBytes(Path.of("examples/reference.an2"));
            var r=NistValidator.validate(fixture);
            check(Arrays.equals(Files.readAllBytes(Path.of("examples/synthetic.png")),r.image()),"reference PNG");
            check(r.width()==32 && r.height()==40,"reference dimensions");
        });
        System.out.println("ALL " + passed + " TESTS PASSED");
    }

    static Properties properties() {
        Properties p=new Properties();
        p.setProperty("transaction.type","TEST");p.setProperty("transaction.date","20261105");
        p.setProperty("destination","TEST_RECEIVER");p.setProperty("origin","TEST_SENDER");
        p.setProperty("transaction.control","TEST-20261105-0001");p.setProperty("domain.name","DEMO");p.setProperty("domain.version","1");
        p.setProperty("capture.source","TEST_CAMERA_OWNER");p.setProperty("capture.date","20261104");
        p.setProperty("scale.units","0");p.setProperty("scale.x","1");p.setProperty("scale.y","1");
        p.setProperty("type2.003","SYNTHETIC-SUBJECT");return p;
    }
    static void badProperty(String key,String value) throws Exception { Properties p=properties();p.setProperty(key,value);invalid(()->Config.from(p)); }
    static void test(String name,Test test) throws Exception { test.run();passed++;System.out.println("PASS " + name); }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void invalid(Test test) throws Exception {
        try { test.run(); } catch (IllegalArgumentException | java.io.IOException e) { return; }
        throw new AssertionError("Expected explicit rejection");
    }
    static byte[] image(int w,int h,int type) throws Exception {
        BufferedImage im=new BufferedImage(w,h,type);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)im.setRGB(x,y,0xff000000|((x*7&255)<<16)|((y*5&255)<<8)|((x+y)&255));
        return encode(im);
    }
    static byte[] encode(BufferedImage im) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream();check(ImageIO.write(im,"PNG",b),"PNG writer");return b.toByteArray(); }
    static BufferedImage image16(boolean alpha) {
        var model=new java.awt.image.ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
            alpha,false,alpha?java.awt.Transparency.TRANSLUCENT:java.awt.Transparency.OPAQUE,java.awt.image.DataBuffer.TYPE_USHORT);
        var raster=model.createCompatibleWritableRaster(32,40);
        BufferedImage im=new BufferedImage(model,raster,false,null);
        for(int y=0;y<40;y++)for(int x=0;x<32;x++) {
            raster.setSample(x,y,0,x*1901);raster.setSample(x,y,1,y*1499);raster.setSample(x,y,2,12345);
            if(alpha)raster.setSample(x,y,3,65535);
        }
        return im;
    }
    static byte[] addChunk(byte[] png,String name,byte[] payload) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(png,0,33);
        byte[] chunk=new byte[payload.length+12];putInt(chunk,0,payload.length);
        System.arraycopy(name.getBytes(StandardCharsets.US_ASCII),0,chunk,4,4);System.arraycopy(payload,0,chunk,8,payload.length);
        crc(chunk,4,payload.length+4,payload.length+8);b.write(chunk);b.write(png,33,png.length-33);return b.toByteArray();
    }
    static void crc(byte[] b,int offset,int count,int target) {CRC32 c=new CRC32();c.update(b,offset,count);putInt(b,target,(int)c.getValue());}
    static void putInt(byte[] b,int p,int v) {for(int i=3;i>=0;i--){b[p+i]=(byte)v;v>>>=8;}}
    static int index(byte[] b,byte[] needle) {
        outer: for(int i=0;i<=b.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(b[i+j]!=needle[j])continue outer;return i;}
        throw new AssertionError("Pattern not found");
    }
    static byte[] replace(byte[] b,String from,String to) {
        byte[] f=from.getBytes(StandardCharsets.US_ASCII),t=to.getBytes(StandardCharsets.US_ASCII);
        check(f.length==t.length,"mutation must preserve byte length");byte[] out=b.clone();System.arraycopy(t,0,out,index(out,f),t.length);return out;
    }
}
