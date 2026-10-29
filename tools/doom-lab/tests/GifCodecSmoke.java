package expo.modules.notificationmotion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;

final class DoomGifEncoder {
    static { System.loadLibrary("noti_doom"); }
    native byte[] encode(int[] pixels, int budget);
    native int sampleStep();
    native int encodedSize();
}
public final class GifCodecSmoke {
    public static void main(String[] args) throws Exception {
        DoomGifEncoder encoder = new DoomGifEncoder();
        if (encoder.encode(null,32768) != null || encoder.encode(new int[1],32768) != null
            || encoder.encode(new int[64000],7) != null || encoder.encode(new int[64000],16384) != null)
            throw new AssertionError("invalid encoder arguments accepted");
        Random random = new Random(109);
        for (int budget : new int[]{32768,65536}) for (int fixture = 0; fixture < 12; ++fixture) {
            int[] pixels = new int[320 * 200];
            for (int i = 0; i < pixels.length; ++i) pixels[i] = 0xff000000 | (fixture == 0 ? 0 : fixture == 1 ? 0xffffff : fixture == 2 ? i & 255 : random.nextInt(0x1000000));
            if (fixture == 3 && args.length > 0) {
                byte[] ppm = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
                int at = ppm.length - pixels.length * 3;
                for (int i = 0; i < pixels.length; ++i) pixels[i] = 0xff000000 | (ppm[at++]&255)<<16 | (ppm[at++]&255)<<8 | ppm[at++]&255;
            }
            byte[] encoded = encoder.encode(pixels, budget);
            int step = encoder.sampleStep();
            if (encoded == null) throw new AssertionError("encoding failed: fixture="+fixture+" step="+step);
            if (fixture == 3 && args.length > 0 && budget == 65536 && step != 1) throw new AssertionError("Real Doom fixture lost resolution: step="+step);
            if (fixture == 3) System.out.println("Doom fixture: budget="+budget+" step="+step+" compressedBytes="+encoder.encodedSize());
            ByteArrayOutputStream gif = new ByteArrayOutputStream();
            gif.write("GIF89a".getBytes()); gif.write(new byte[]{(byte)184,3,(byte)83,2,(byte)247,0,0});
            for (int c = 0; c < 256; ++c) { gif.write(((c >> 5) & 7)*255/7); gif.write(((c >> 2)&7)*255/7); gif.write((c&3)*255/3); }
            gif.write(encoded); gif.write(0x3b);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(gif.toByteArray()));
            if (image == null || image.getWidth() != 952 || image.getHeight() != 595) throw new AssertionError("shape");
            for (int y = 0; y < 595; ++y) for (int x = 0; x < 952; ++x) {
                int argb = pixels[(y*200/595/step*step)*320+x*320/952/step*step]; int index = ((argb>>16)&224)|((argb>>11)&28)|((argb>>6)&3);
                int expected = 0xff000000 | (((index>>5)&7)*255/7)<<16 | (((index>>2)&7)*255/7)<<8 | (index&3)*255/3;
                if (image.getRGB(x,y) != expected) throw new AssertionError("fixture="+fixture+" pixel="+x+","+y);
            }
        }
        System.out.println("PASS: 24 native GIF fixtures decoded pixel-exactly by the independent JVM GIF decoder");
    }
}
