package dev.noti.flipperlab;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/** Isolated transport microbenchmark. It does not measure notify-to-pixel latency. */
public final class DoomTransportActivity extends Activity {
    private static final int WIDTH = 320, HEIGHT = 200, COUNT = 80;
    private final int[] pixels = new int[WIDTH * HEIGHT];
    private final JSONArray samples = new JSONArray();
    private final Handler handler = new Handler();
    private TextView status;
    private FrameLayout parent;
    private int step = -20;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setPadding(32, 96, 32, 32);
        status.setTextSize(18);
        status.setText("Doom transport probe · running\n320 × 200 · separate provider process");
        setContentView(status);
        parent = new FrameLayout(this);
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | ((i * 1103515245 + 12345) & 0xffffff);
        try {
            try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "doom-transport.bmp"))) {
                stream.write(encode());
            }
            handler.postDelayed(this::next, 500);
        } catch (Exception error) { fail(error); }
    }

    private byte[] encode() {
        byte[] out = new byte[54 + WIDTH * HEIGHT * 3];
        out[0] = 'B'; out[1] = 'M'; out[26] = 1; out[28] = 24;
        put(out, 2, out.length); put(out, 10, 54); put(out, 14, 40);
        put(out, 18, WIDTH); put(out, 22, HEIGHT); put(out, 34, WIDTH * HEIGHT * 3);
        int at = 54;
        for (int y = HEIGHT - 1; y >= 0; y--) for (int x = 0; x < WIDTH; x++) {
            int color = pixels[y * WIDTH + x];
            out[at++] = (byte) color; out[at++] = (byte) (color >> 8); out[at++] = (byte) (color >> 16);
        }
        return out;
    }

    private static void put(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >> (8 * i));
    }

    private JSONObject measure(boolean direct) throws Exception {
        long started = SystemClock.elapsedRealtimeNanos();
        Bitmap source = direct ? Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888) : null;
        // Doom keeps encoded bytes in its provider's memory. The provider below caches the
        // same bytes, while this encode measures the producer's per-frame CPU/allocation cost.
        byte[] encoded = direct ? null : encode();
        long prepared = SystemClock.elapsedRealtimeNanos();
        RemoteViews image = new RemoteViews(getPackageName(), R.layout.image);
        if (direct) image.setImageViewBitmap(R.id.image, source);
        else image.setImageViewUri(R.id.image, Uri.parse("content://dev.noti.flipperlab.doomprobe/frame.bmp?sample=" + step));
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.manual);
        views.addView(R.id.flip, image);
        Parcel parcel = Parcel.obtain();
        try {
            views.writeToParcel(parcel, 0);
            int bytes = parcel.dataSize();
            parcel.setDataPosition(0);
            RemoteViews received = RemoteViews.CREATOR.createFromParcel(parcel);
            long transported = SystemClock.elapsedRealtimeNanos();
            View result = received.apply(this, parent);
            long inflated = SystemClock.elapsedRealtimeNanos();
            ImageView displayed = result.findViewById(R.id.image);
            Bitmap decoded = ((BitmapDrawable) displayed.getDrawable()).getBitmap();
            if (decoded.getWidth() != WIDTH || decoded.getHeight() != HEIGHT || decoded.getPixel(91, 77) != pixels[77 * WIDTH + 91]) {
                throw new IllegalStateException("Transport changed image pixels");
            }
            JSONObject row = new JSONObject();
            row.put("mode", direct ? "bitmap" : "bmp-memfd-uri");
            row.put("prepareMs", (prepared - started) / 1e6);
            row.put("remoteViewsAndParcelMs", (transported - prepared) / 1e6);
            row.put("inflateMs", (inflated - transported) / 1e6);
            row.put("totalMs", (inflated - started) / 1e6);
            row.put("parcelBytes", bytes);
            row.put("sourceBytes", direct ? source.getAllocationByteCount() : encoded.length);
            return row;
        } finally {
            parcel.recycle();
            if (source != null) source.recycle();
        }
    }

    private void next() {
        try {
            // Alternate each pair's order, keeping warm-up and measured operations identical.
            boolean firstDirect = (step & 1) == 0;
            JSONObject a = measure(firstDirect), b = measure(!firstDirect);
            if (step >= 0) { samples.put(a); samples.put(b); }
            if (++step < COUNT) { handler.postDelayed(this::next, 16); return; }
            JSONObject result = new JSONObject();
            result.put("sdk", android.os.Build.VERSION.SDK_INT);
            result.put("fingerprint", android.os.Build.FINGERPRINT);
            result.put("complete", true);
            result.put("scope", "Warm isolated RemoteViews preparation, parcel round trip and synchronous inflation; provider is another process. No notification service, SystemUI load, GPU presentation or touch latency is measured.");
            result.put("samplesPerMode", COUNT);
            result.put("warmupPairs", 20);
            result.put("dimensions", new JSONArray(Arrays.asList(WIDTH, HEIGHT)));
            Bundle provider = getContentResolver().call(Uri.parse("content://dev.noti.flipperlab.doomprobe"), "process", null, null);
            int providerPid = provider.getInt("pid");
            if (providerPid == android.os.Process.myPid()) throw new IllegalStateException("Provider was not separate");
            result.put("hostProcess", android.app.Application.getProcessName());
            result.put("hostPid", android.os.Process.myPid());
            result.put("providerPid", providerPid);
            result.put("providerProcessIsSeparate", true);
            result.put("samples", samples);
            try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "doom-transport.json"))) {
                stream.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            status.setText("Doom transport probe · complete\n80 samples per mode, pixels verified\nNot an input-latency measurement");
        } catch (Exception error) { fail(error); }
    }

    private void fail(Exception error) { status.setText("Doom transport probe failed: " + error); }

    public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

    public static final class BmpProvider extends ContentProvider {
        private byte[] data;
        public boolean onCreate() { return true; }
        public String getType(Uri uri) { return "image/bmp"; }
        public Bundle call(String method, String arg, Bundle extras) {
            if (!"process".equals(method)) throw new IllegalArgumentException("Unknown probe call");
            Bundle result = new Bundle(); result.putInt("pid", android.os.Process.myPid()); return result;
        }
        public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!"r".equals(mode) || !"/frame.bmp".equals(uri.getPath())) throw new FileNotFoundException("Unknown probe frame");
            try {
                if (data == null) data = Files.readAllBytes(new File(getContext().getFilesDir(), "doom-transport.bmp").toPath());
                java.io.FileDescriptor fd = Os.memfd_create("doom-transport", OsConstants.MFD_CLOEXEC);
                try {
                    int written = 0;
                    while (written < data.length) written += Os.write(fd, data, written, data.length - written);
                    Os.lseek(fd, 0, OsConstants.SEEK_SET);
                    return ParcelFileDescriptor.dup(fd);
                } finally { Os.close(fd); }
            } catch (Exception error) { throw new FileNotFoundException(error.toString()); }
        }
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
}
