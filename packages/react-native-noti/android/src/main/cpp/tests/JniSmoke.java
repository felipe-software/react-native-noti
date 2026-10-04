// SPDX-License-Identifier: GPL-2.0-or-later
package expo.modules.notificationmotion;

// Host-only fixture with the exact binary name and descriptors of the Kotlin object.
final class DoomEngine {
    static { System.loadLibrary("noti_doom"); }
    native boolean start(String path);
    native int[] render();
    native void key(int key, boolean pressed);
    native void stop();
}

public final class JniSmoke {
    public static void main(String[] args) throws Exception {
        DoomEngine engine = new DoomEngine();
        if (engine.render() != null || engine.start(null)) throw new AssertionError("uninitialized state");
        if (!engine.start(args[0])) throw new AssertionError("start");
        Thread.sleep(200);
        int[] frame = engine.render();
        if (frame == null || frame.length != 320 * 200) throw new AssertionError("frame shape");
        for (int pixel : frame) if ((pixel >>> 24) != 255) throw new AssertionError("alpha");
        engine.key(0xAD, true);
        engine.key(0xA3, true);
        Thread.sleep(250);
        engine.key(0xAD, false);
        engine.key(0xA3, false);
        engine.stop();
        if (engine.render() != null) throw new AssertionError("stopped frame");
        if (!engine.start(args[0])) throw new AssertionError("resume");
        engine.stop();
        System.out.println("PASS: exact DoomEngine JNI signatures, 64000 ARGB pixels, key, stop/resume");
    }
}
