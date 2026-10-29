package dev.noti.flipperlab;

import android.widget.RemoteViews;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;

final class RemoteDocument {
    // Wire format pinned to AOSP android-16.0.0_r1; preserve NaN payload bits for remote IDs.
    static byte[] bytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0);
        out.writeInt(1);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(400);
        out.writeInt(200);
        out.writeLong(0);
        out.writeByte(65);
        out.writeInt(0);
        out.writeInt(34);
        out.writeInt(2);
        out.writeInt(4);
        out.writeByte(81);
        out.writeInt(42);
        int[] expression = {ref(1), bits(1.5f), ref(0x310003), ref(0x310012), bits(120), ref(0x310003), bits(200), ref(0x310001)};
        out.writeInt(expression.length);
        for (int value : expression) out.writeInt(value);
        out.writeByte(200);
        out.writeInt(100);
        out.writeByte(40);
        out.writeInt(2);
        out.writeInt(4);
        out.writeInt(0xff41c9aa);
        out.writeByte(46);
        out.writeInt(ref(42));
        out.writeInt(bits(100));
        out.writeInt(bits(24));
        out.writeByte(214);
        return bytes.toByteArray();
    }

    private static int bits(float value) { return Float.floatToRawIntBits(value); }
    private static int ref(int id) { return id | 0xff800000; }

    static RemoteViews views() throws IOException {
        return new RemoteViews(new RemoteViews.DrawInstructions.Builder(Collections.singletonList(bytes())).build());
    }

    static long supportedVersion() { return RemoteViews.DrawInstructions.getSupportedVersion(); }
}
