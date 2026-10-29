// SPDX-License-Identifier: GPL-2.0-or-later
package expo.modules.notificationmotion;

// Host-only fixture with the exact binary name and descriptors of the Kotlin object.
final class DoomEngine {
    static { System.loadLibrary("noti_doom"); }
    native boolean start(String path);
    native int[] render();
    native int[] renderState(long[] state);
    native void key(int key, boolean pressed);
    native long keyTracked(int key, boolean pressed);
    native void stop();
}

public final class JniSmoke {
    public static void main(String[] args) throws Exception {
        DoomEngine engine = new DoomEngine();
        if (engine.render() != null || engine.start(null)) throw new AssertionError("uninitialized state");
        if (engine.renderState(new long[5]) != null || engine.keyTracked(0xAD, true) != 0) throw new AssertionError("uninitialized tracked state");
        for (long[] invalid : new long[][] { null, new long[4] }) {
            try { engine.renderState(invalid); throw new AssertionError("invalid metadata accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        if (!engine.start(args[0])) throw new AssertionError("start");
        Thread.sleep(200);
        int[] frame = engine.render();
        if (frame == null || frame.length != 320 * 200) throw new AssertionError("frame shape");
        for (int pixel : frame) if ((pixel >>> 24) != 255) throw new AssertionError("alpha");
        long input = engine.keyTracked(0xAE, true);
        long[] metadata = new long[5];
        long deadline = System.nanoTime() + 500_000_000L;
        do {
            frame = engine.renderState(metadata);
            if (frame != null && metadata[1] >= input && metadata[2] < input) break;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        if (input <= 0 || frame == null || metadata[0] <= 0 || metadata[1] < input || metadata[2] >= input || metadata[3] <= 0 || metadata[4] <= 0) {
            throw new AssertionError("tracked input was not acknowledged by a rendered frame");
        }
        engine.key(0xAE, false);
        engine.key(0xAD, true);
        engine.key(0xA3, true);
        Thread.sleep(250);
        engine.key(0xAD, false);
        engine.key(0xA3, false);
        engine.stop();
        if (engine.render() != null) throw new AssertionError("stopped frame");
        if (!engine.start(args[0])) throw new AssertionError("resume");
        engine.stop();
        System.out.println("PASS: DoomEngine JNI signatures, 64000 ARGB pixels, rendered input acknowledgement, metadata validation, key, stop/resume");
    }
}
