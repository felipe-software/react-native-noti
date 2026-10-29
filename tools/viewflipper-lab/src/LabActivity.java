package dev.noti.flipperlab;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.AdapterViewFlipper;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.ViewFlipper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LabActivity extends Activity {
    static final String PKG = "dev.noti.flipperlab";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private JSONArray results = new JSONArray();
    private FrameLayout stage;
    private TextView status;
    private AppWidgetHost host;
    private int widgetId;
    private boolean running;
    private int generation;
    private final Runnable startSuite = this::runSuite;
    private final Runnable startRates = this::runRates;

    interface Probe { Object run() throws Exception; }

    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 80, 32, 32);
        root.setBackgroundColor(Color.rgb(240, 239, 234));
        status = new TextView(this);
        status.setTextSize(18);
        status.setText("ViewFlipper Lab · API " + android.os.Build.VERSION.SDK_INT);
        root.addView(status);
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, dp(220)));
        button(root, "Run experiments", () -> runSuite());
        button(root, "Notification · 30 fps / 120 frames", () -> demo("frames", 33));
        button(root, "Notification · URI frames", () -> demo("uri", 33));
        button(root, "Notification · widget collection", () -> demo("adapter", 100));
        button(root, "Notification · native vector expressions", () -> demo("draw", 33));
        button(root, "Pin animation widget", this::pinWidget);
        button(root, "Clear lab notifications", () -> getSystemService(NotificationManager.class).cancelAll());
        setContentView(root);
        host = new AppWidgetHost(this, 704);
        host.startListening();
        route(getIntent());
    }

    private void button(LinearLayout root, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        root.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
    }

    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route(intent);
    }

    private void route(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) return;
        handler.removeCallbacks(startSuite);
        handler.removeCallbacks(startRates);
        if (running) {
            generation++;
            running = false;
            queue.clear();
            handler.removeCallbacksAndMessages(null);
        }
        if ("run".equals(uri.getHost())) handler.postDelayed(startSuite, 400);
        if ("rates".equals(uri.getHost())) handler.postDelayed(startRates, 400);
        if ("pin".equals(uri.getHost())) pinWidget();
        if ("demo".equals(uri.getHost())) {
            String kind = uri.getQueryParameter("kind");
            String interval = uri.getQueryParameter("interval");
            demo(kind == null ? "frames" : kind, interval == null ? 33 : Integer.parseInt(interval));
        }
        if ("cleanup".equals(uri.getHost())) {
            getSystemService(NotificationManager.class).cancelAll();
            host.deleteHost();
            status.setText("Lab notifications and private widget host cleared");
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private RemoteViews layout(int id) { return new RemoteViews(PKG, id); }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        boolean supported = manager.isRequestPinAppWidgetSupported();
        boolean accepted = supported && manager.requestPinAppWidget(new ComponentName(this, LabWidget.class), null, null);
        try {
            JSONObject result = details("sdk", android.os.Build.VERSION.SDK_INT, "pinningSupported", supported, "requestAccepted", accepted);
            try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "pin-request.json"))) {
                stream.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            status.setText("Widget pin support: " + supported + " · request accepted: " + accepted);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static RemoteViews cell(Context context, int index) {
        RemoteViews views = new RemoteViews(PKG, R.layout.cell);
        views.setTextViewText(R.id.label, String.format(java.util.Locale.US, "Frame %03d", index));
        views.setInt(R.id.label, "setBackgroundColor", Color.HSVToColor(new float[]{(index * 47) % 360, .75f, .55f}));
        views.setContentDescription(R.id.label, "frame-" + index);
        return views;
    }

    static RemoteViews frames(Context context, int count, int interval) {
        RemoteViews views = new RemoteViews(PKG, R.layout.flip);
        views.setInt(R.id.flip, "setFlipInterval", interval);
        for (int i = 0; i < count; i++) views.addView(R.id.flip, cell(context, i));
        return views;
    }

    private RemoteViews manual(int layout, int count) {
        RemoteViews views = layout(layout);
        for (int i = 0; i < count; i++) views.addView(R.id.flip, cell(this, i));
        return views;
    }

    private RemoteViews adapter(int count) {
        RemoteViews views = layout(R.layout.adapter);
        RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1);
        for (int i = 0; i < count; i++) items.addItem(i, cell(this, i));
        views.setRemoteAdapter(R.id.adapter, items.build());
        return views;
    }

    private int parcelSize(RemoteViews views) {
        Parcel parcel = Parcel.obtain();
        try { views.writeToParcel(parcel, 0); return parcel.dataSize(); }
        finally { parcel.recycle(); }
    }

    private RemoteViews transport(RemoteViews views) {
        Parcel parcel = Parcel.obtain();
        try {
            views.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return RemoteViews.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    private View apply(RemoteViews views) { return transport(views).apply(this, stage); }
    private void show(View view) {
        stage.removeAllViews();
        stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private JSONObject details(Object... values) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private void record(String name, String expected, Object value, Throwable error) {
        try {
            JSONObject row = details("name", name, "expected", expected, "uptimeMs", SystemClock.uptimeMillis());
            boolean unsupported = "unsupported".equals(expected) && error == null;
            row.put("outcome", unsupported ? "unsupported" : error == null ? "accepted" : "rejected");
            row.put("matchesExpectation", "observe".equals(expected) || ("unsupported".equals(expected) ? error == null : (error == null) == "accept".equals(expected)));
            if (error == null) row.put("value", value == null ? JSONObject.NULL : value);
            else {
                row.put("error", error.toString());
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                row.put("rootCause", cause.toString());
            }
            results.put(row);
            status.setText(results.length() + " experiments · " + name);
            Log.i("FlipperLab", row.toString());
            persist(false);
        } catch (Exception errorWriting) { throw new IllegalStateException(errorWriting); }
    }

    private void persist(boolean complete) throws Exception {
        JSONObject report = details("schema", 1, "sdk", android.os.Build.VERSION.SDK_INT, "fingerprint", android.os.Build.FINGERPRINT,
            "manufacturer", android.os.Build.MANUFACTURER, "model", android.os.Build.MODEL,
            "widthPixels", getResources().getDisplayMetrics().widthPixels, "heightPixels", getResources().getDisplayMetrics().heightPixels,
            "density", getResources().getDisplayMetrics().density, "complete", complete, "results", results);
        File temporary = new File(getFilesDir(), "results.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!temporary.renameTo(new File(getFilesDir(), "results.json"))) throw new java.io.IOException("Cannot save results");
    }

    private void test(String name, String expected, Probe probe) {
        queue.add(() -> {
            try { record(name, expected, probe.run(), null); }
            catch (Throwable error) { record(name, expected, null, error); }
            next();
        });
    }

    private void later(String name, int milliseconds, Probe setup, Probe check) {
        queue.add(() -> {
            try { setup.run(); }
            catch (Throwable error) { record(name, "accept", null, error); next(); return; }
            handler.postDelayed(() -> {
                try { record(name, "accept", check.run(), null); }
                catch (Throwable error) { record(name, "accept", null, error); }
                next();
            }, milliseconds);
        });
    }

    private void next() {
        if (queue.isEmpty()) {
            running = false;
            stage.removeAllViews();
            try { persist(true); } catch (Exception error) { throw new IllegalStateException(error); }
            status.setText("Complete · " + results.length() + " experiments · results.json");
            Log.i("FlipperLab", "COMPLETE " + results.length());
            return;
        }
        handler.postDelayed(queue.removeFirst(), 40);
    }

    private void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private void runRates() {
        if (running) return;
        running = true;
        results = new JSONArray();
        queue.clear();
        for (int rate : new int[]{2, 4, 8, 20}) {
            queue.add(() -> handler.postDelayed(() -> rate(rate), 4000));
        }
        next();
    }

    private void rate(int rate) {
        int count = rate * 8;
        int id = 10000 + rate;
        long start = SystemClock.uptimeMillis();
        JSONArray offered = new JSONArray();
        Runnable send = new Runnable() {
            int index;
            public void run() {
                try {
                    long now = SystemClock.uptimeMillis();
                    Notification notification = notification("Rate " + rate + " / sec · " + index, manual(R.layout.manual, 2));
                    notification.extras.putInt("lab_sequence", index);
                    notification.extras.putLong("lab_sent_uptime", now);
                    getSystemService(NotificationManager.class).notify(id, notification);
                    offered.put(details("sequence", index, "uptimeMs", now));
                    index++;
                    if (index < count) handler.postAtTime(this, start + index * 1000L / rate);
                    else handler.postDelayed(() -> {
                        try {
                            android.service.notification.StatusBarNotification row = active(id);
                            record("notification/rate-" + rate, "accept", details("requestedRate", rate, "attempts", count,
                                "durationMs", SystemClock.uptimeMillis() - start, "lastAcceptedSequence", row == null ? -1 : row.getNotification().extras.getInt("lab_sequence", -1), "offered", offered), null);
                            getSystemService(NotificationManager.class).cancel(id);
                        } catch (Throwable error) { record("notification/rate-" + rate, "accept", null, error); }
                        next();
                    }, 1000);
                } catch (Throwable error) { record("notification/rate-" + rate, "accept", null, error); next(); }
            }
        };
        send.run();
    }

    private void runSuite() {
        if (running) return;
        running = true;
        generation++;
        results = new JSONArray();
        queue.clear();
        String[] names = {"FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "TextView", "Button", "ImageView", "ImageButton", "ProgressBar", "Chronometer", "TextClock", "AnalogClock", "ViewFlipper", "AdapterViewFlipper", "ListView", "GridView", "StackView", "CheckBox", "Switch", "RadioButton", "RadioGroup", "View", "Space", "EditText", "WebView", "SurfaceView", "TextureView", "VideoView", "ScrollView", "CustomText"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            test("inflate/" + names[i], i <= 20 ? "accept" : "reject", () -> apply(layout(getResources().getIdentifier("class_" + index, "layout", PKG))).getClass().getName());
        }
        test("methods/remotable-inventory", "accept", () -> methodInventory());
        for (String method : new String[]{"setAlpha", "setRotation", "setRotationX", "setRotationY", "setScaleX", "setScaleY", "setTranslationX", "setTranslationY", "setTranslationZ", "setElevation"}) {
            test("method/" + method, "observe", () -> {
                RemoteViews views = manual(R.layout.manual, 2);
                views.setFloat(R.id.flip, method, .5f);
                apply(views);
                return "applied";
            });
        }
        for (String method : new String[]{"setAutoStart", "setAnimateFirstView"}) {
            test("method/" + method, "reject", () -> {
                RemoteViews views = manual(R.layout.manual, 2);
                views.setBoolean(R.id.flip, method, true);
                return apply(views).getClass().getName();
            });
        }
        test("method/AdapterViewFlipper.setFlipInterval", "reject", () -> {
            RemoteViews views = layout(R.layout.adapter);
            views.setInt(R.id.adapter, "setFlipInterval", 33);
            return apply(views).getClass().getName();
        });
        for (int interval : new int[]{-1, 0, 1, 8, 16, 33, 1000}) {
            test("interval/accepts-" + interval, "accept", () -> {
                RemoteViews views = manual(R.layout.manual, 2);
                views.setInt(R.id.flip, "setFlipInterval", interval);
                return ((ViewFlipper) apply(views)).getFlipInterval();
            });
        }
        for (int count : new int[]{1, 13, 60, 120, 512}) {
            test("children/" + count, "accept", () -> {
                RemoteViews views = manual(R.layout.manual, count);
                long start = SystemClock.elapsedRealtimeNanos();
                ViewFlipper flip = (ViewFlipper) apply(views);
                require(flip.getChildCount() == count, "Wrong child count");
                return details("children", flip.getChildCount(), "parcelBytes", parcelSize(views), "inflateMs", (SystemClock.elapsedRealtimeNanos() - start) / 1e6);
            });
        }
        for (int depth : new int[]{9, 10, 11, 12}) {
            test("nested-RemoteViews/" + depth, depth <= 10 ? "accept" : "reject", () -> {
                RemoteViews views = cell(this, 0);
                for (int level = 0; level < depth; level++) {
                    RemoteViews parent = layout(R.layout.group);
                    parent.addView(R.id.holder, views);
                    views = parent;
                }
                return apply(views).getClass().getName();
            });
        }
        test("xml-depth/32", "accept", () -> apply(layout(R.layout.deep_xml)).getClass().getName());
        test("index/negative-and-overflow", "accept", () -> {
            RemoteViews views = manual(R.layout.manual, 13);
            views.setDisplayedChild(R.id.flip, -6);
            ViewFlipper flip = (ViewFlipper) apply(views);
            int negative = flip.getDisplayedChild();
            RemoteViews update = layout(R.layout.manual);
            update.setDisplayedChild(R.id.flip, 99);
            update.reapply(this, flip);
            require(negative == 12 && flip.getDisplayedChild() == 0, "Unexpected clamp behavior");
            return details("negativeIndex", negative, "overflowIndex", flip.getDisplayedChild());
        });
        test("reapply/preserve-and-reset-index", "accept", () -> {
            RemoteViews views = manual(R.layout.manual, 13);
            views.setDisplayedChild(R.id.flip, 7);
            ViewFlipper flip = (ViewFlipper) apply(views);
            RemoteViews update = layout(R.layout.manual);
            update.setContentDescription(R.id.flip, "changed");
            update.reapply(this, flip);
            int preserved = flip.getDisplayedChild();
            update.setDisplayedChild(R.id.flip, 0);
            update.reapply(this, flip);
            require(preserved == 7 && flip.getDisplayedChild() == 0, "Index preservation failed");
            return details("preserved", preserved, "explicitReset", flip.getDisplayedChild());
        });
        test("bitmap/cache-identity", "accept", () -> {
            Bitmap same = bitmap(128, 128, 0);
            RemoteViews reused = images(20, 128, true, same);
            RemoteViews unique = images(20, 128, false, null);
            return details("sharedInputBitmapBytes", 128 * 128 * 4, "distinctInputBitmapBytes", 20 * 128 * 128 * 4, "sharedParcelBytes", parcelSize(reused), "distinctParcelBytes", parcelSize(unique));
        });
        test("bitmap/URI-accounting", "accept", () -> {
            RemoteViews views = uriFrames(96);
            ViewFlipper flip = (ViewFlipper) apply(views);
            int decoded = 0;
            for (int i = 0; i < flip.getChildCount(); i++) if (((ImageView) flip.getChildAt(i)).getDrawable() != null) decoded++;
            require(decoded == 96, "Some URI frames did not decode");
            return details("inlineBitmapBytes", 0, "parcelBytes", parcelSize(views), "decodedFrames", decoded, "decodedArgbBytes", 96 * 160 * 120 * 4);
        });
        final ViewFlipper[] flip = new ViewFlipper[1];
        final int[] previous = new int[1];
        later("lifecycle/GONE-child-keeps-ticking", 650, () -> {
            flip[0] = (ViewFlipper) apply(frames(this, 120, 33));
            show(flip[0]);
            flip[0].setVisibility(View.GONE);
            previous[0] = flip[0].getDisplayedChild();
            return null;
        }, () -> details("before", previous[0], "after", flip[0].getDisplayedChild(), "isShown", flip[0].isShown(), "started", flip[0].isFlipping()));
        later("lifecycle/detached-stops-ticking", 650, () -> {
            previous[0] = flip[0].getDisplayedChild();
            stage.removeAllViews();
            return null;
        }, () -> { require(previous[0] == flip[0].getDisplayedChild(), "Detached timer advanced"); return details("before", previous[0], "after", flip[0].getDisplayedChild(), "isFlippingStillReturns", flip[0].isFlipping()); });
        later("animation/one-child-infinite-XML", 450, () -> {
            RemoteViews views = manual(R.layout.loop, 1);
            views.setDisplayedChild(R.id.flip, 0);
            flip[0] = (ViewFlipper) apply(views);
            show(flip[0]);
            return null;
        }, () -> {
            Animation animation = flip[0].getChildAt(0).getAnimation();
            require(animation != null && animation.hasStarted(), "XML animation not running");
            return details("childCount", 1, "started", animation.hasStarted(), "repeatCount", animation.getRepeatCount(), "durationMs", animation.getDuration());
        });
        final View[] property = new View[1];
        later("animation/state-list-without-flipper", 450, () -> {
            View root = apply(layout(R.layout.property_motion));
            property[0] = root.findViewById(R.id.label);
            show(root);
            return null;
        }, () -> {
            require(property[0].getTranslationX() > 0, "Property animation did not move");
            return details("translationX", property[0].getTranslationX(), "rotation", property[0].getRotation(), "flipperCount", 0);
        });
        final View[] touchRoot = new View[1];
        final int[] hits = new int[1];
        later("interaction/legacy-animation-hit-geometry", 100, () -> {
            touchRoot[0] = apply(layout(R.layout.legacy_hit));
            View target = touchRoot[0].findViewById(R.id.target);
            target.setOnClickListener(view -> {});
            target.setOnTouchListener((view, event) -> { if (event.getAction() == MotionEvent.ACTION_DOWN) hits[0]++; return false; });
            show(touchRoot[0]);
            return null;
        }, () -> {
            int original = dispatchTap(touchRoot[0], dp(20), dp(20), hits);
            int drawn = dispatchTap(touchRoot[0], 120 + dp(60), dp(20), hits);
            require(original + drawn > 0, "No touch reached target; hit test is inconclusive");
            return details("originalBoundsTouchDowns", original, "translatedVisualBoundsTouchDowns", drawn, "visualOffsetPixels", 120);
        });
        if (supportsDrawInstructions()) {
            test("draw-instructions/public-api-and-inflation", "accept", () -> {
                RemoteViews views = RemoteDocument.views();
                View player = apply(views);
                show(player);
                return details("supportedDocumentApiLevel", RemoteDocument.supportedVersion(), "wireBytes", RemoteDocument.bytes().length,
                    "parcelBytes", parcelSize(views), "playerClass", player.getClass().getName());
            });
        } else {
            test("draw-instructions/public-api-and-inflation", "unsupported", () -> details("sdk", android.os.Build.VERSION.SDK_INT, "reason", "android.widget.RemoteViews$DrawInstructions is absent"));
        }
        for (int interval : new int[]{1, 8, 16, 33, 66}) queue.add(() -> measure(interval));
        test("widget/bind-private-host", "accept", () -> {
            widgetId = host.allocateAppWidgetId();
            boolean bound = AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, new ComponentName(this, LabWidget.class));
            require(bound, "Run adb shell appwidget grantbind --package " + PKG);
            return widgetId;
        });
        final AppWidgetHostView[] widgetView = new AppWidgetHostView[1];
        later("widget/collection-150-recycles", 900, () -> {
            RemoteViews views = adapter(150);
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            manager.updateAppWidget(widgetId, views);
            widgetView[0] = host.createView(this, widgetId, manager.getAppWidgetInfo(widgetId));
            widgetView[0].updateAppWidget(views);
            show(widgetView[0]);
            return null;
        }, () -> {
            AdapterViewFlipper adapter = widgetView[0].findViewById(R.id.adapter);
            require(adapter != null && adapter.getAdapter() != null, "No widget collection adapter");
            return details("items", adapter.getAdapter().getCount(), "activeChildViews", adapter.getChildCount(), "displayedIndex", adapter.getDisplayedChild(), "parcelBytes", parcelSize(adapter(150)));
        });
        test("notification/collection-without-widget-host", "accept", () -> {
            AdapterViewFlipper adapter = (AdapterViewFlipper) apply(adapter(150));
            require(adapter.getAdapter() == null, "Collection unexpectedly bound outside widget host");
            return details("adapterBound", false);
        });
        for (int count : new int[]{3, 4}) {
            test("widget/bitmap-" + count + "x1024", count == 3 ? "accept" : "reject", () -> {
                RemoteViews views = images(count, 1024, false, null);
                AppWidgetManager.getInstance(this).updateAppWidget(widgetId, views);
                return details("inputBitmapBytes", count * 1024 * 1024 * 4, "parcelBytes", parcelSize(views));
            });
        }
        test("widget/restore", "accept", () -> { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, frames(this, 120, 33)); return "restored"; });
        for (int count : new int[]{18, 20}) {
            final int id = 800 + count;
            later("notification/bitmap-" + count + "x256", 900, () -> {
                RemoteViews views = images(count, 256, false, null);
                post(id, "Bitmap budget " + count, views);
                return null;
            }, () -> {
                android.service.notification.StatusBarNotification found = active(id);
                require(found != null, "Notification missing");
                boolean retained = found.getNotification().bigContentView != null;
                require(retained == (count == 18), "Unexpected strip result");
                getSystemService(NotificationManager.class).cancel(id);
                return details("bitmapBytes", count * 256 * 256 * 4, "customViewRetained", retained);
            });
        }
        for (int actions : new int[]{200, 1400}) {
            test("binder/metadata-" + actions, actions == 200 ? "accept" : "reject", () -> {
                RemoteViews views = manual(R.layout.manual, 2);
                String value = String.join("", Collections.nCopies(1000, "x"));
                for (int i = 0; i < actions; i++) views.setContentDescription(R.id.flip, value + i);
                int bytes = parcelSize(views);
                Log.i("FlipperLab", "BINDER_ATTEMPT " + actions + " " + bytes);
                post(900 + actions, "Binder metadata", views);
                getSystemService(NotificationManager.class).cancel(900 + actions);
                return details("parcelBytes", bytes, "actions", actions);
            });
        }
        test("widget/cleanup-private-host", "accept", () -> { host.deleteHost(); return "removed"; });
        next();
    }

    private JSONArray methodInventory() throws Exception {
        JSONArray result = new JSONArray();
        for (Class<?> type : new Class<?>[]{ViewFlipper.class, AdapterViewFlipper.class, ImageView.class, TextView.class}) {
            List<String> methods = new ArrayList<>();
            for (Method method : type.getMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().equals("android.view.RemotableViewMethod")) methods.add(method.toString());
                }
            }
            Collections.sort(methods);
            result.put(details("class", type.getName(), "methods", new JSONArray(methods)));
        }
        return result;
    }

    private boolean supportsDrawInstructions() {
        try { Class.forName("android.widget.RemoteViews$DrawInstructions"); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    private int dispatchTap(View root, float x, float y, int[] hits) {
        int before = hits[0];
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0);
        root.dispatchTouchEvent(down);
        root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        return hits[0] - before;
    }

    private Bitmap bitmap(int width, int height, int index) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.HSVToColor(new float[]{index * 37 % 360, .8f, .7f}));
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(height * .4f);
        canvas.drawText(Integer.toString(index), 8, height * .65f, paint);
        return bitmap;
    }

    private RemoteViews images(int count, int size, boolean share, Bitmap same) {
        RemoteViews views = layout(R.layout.flip);
        for (int i = 0; i < count; i++) {
            RemoteViews image = layout(R.layout.image);
            image.setImageViewBitmap(R.id.image, share ? same : bitmap(size, size, i));
            views.addView(R.id.flip, image);
        }
        return views;
    }

    private RemoteViews uriFrames(int count) throws Exception {
        RemoteViews views = layout(R.layout.flip);
        for (int i = 0; i < count; i++) {
            String filename = "frame_" + i + ".png";
            File path = new File(getFilesDir(), filename);
            if (!path.exists()) {
                Bitmap bitmap = bitmap(160, 120, i);
                try (FileOutputStream stream = new FileOutputStream(path)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
                bitmap.recycle();
            }
            RemoteViews image = layout(R.layout.image);
            image.setImageViewUri(R.id.image, Uri.parse("content://" + PKG + ".frames/" + filename));
            views.addView(R.id.flip, image);
        }
        return views;
    }

    private void measure(int interval) {
        try {
            ViewFlipper flip = (ViewFlipper) apply(frames(this, 120, interval));
            show(flip);
            long start = SystemClock.uptimeMillis();
            int runGeneration = generation;
            JSONArray samples = new JSONArray();
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                int last = -1;
                int changes;
                int selectedSteps;
                int vsyncs;
                public void doFrame(long time) {
                    if (runGeneration != generation) return;
                    long elapsed = SystemClock.uptimeMillis() - start;
                    int index = flip.getDisplayedChild();
                    try {
                        samples.put(details("elapsedMs", elapsed, "index", index));
                        vsyncs++;
                        if (last >= 0 && index != last) changes++;
                        if (last >= 0) selectedSteps += (index - last + 120) % 120;
                        last = index;
                        if (elapsed < 1700) Choreographer.getInstance().postFrameCallback(this);
                        else {
                            record("timing/vsync-sampled-" + interval + "ms", "accept", details("requestedIntervalMs", interval,
                                "durationMs", elapsed, "vsyncSamples", vsyncs, "indexChanges", changes, "selectedSteps", selectedSteps,
                                "changesPerSecond", changes * 1000.0 / elapsed, "samples", samples), null);
                            next();
                        }
                    } catch (Throwable error) { record("timing/" + interval, "accept", null, error); next(); }
                }
            });
        } catch (Throwable error) { record("timing/" + interval, "accept", null, error); next(); }
    }

    private Notification notification(String title, RemoteViews views) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("lab", "ViewFlipper lab", NotificationManager.IMPORTANCE_LOW));
        if (views.getLayoutId() != 0) views.setViewLayoutHeight(views.getLayoutId() == R.layout.adapter ? R.id.adapter : R.id.flip, 200, android.util.TypedValue.COMPLEX_UNIT_DIP);
        return new Notification.Builder(this, "lab").setSmallIcon(R.drawable.icon).setContentTitle(title)
            .setContentText("Native RemoteViews experiment").setCustomContentView(cell(this, 0)).setCustomBigContentView(views)
            .setStyle(new Notification.DecoratedCustomViewStyle()).setOnlyAlertOnce(true).setOngoing(true).build();
    }

    private void post(int id, String title, RemoteViews views) { getSystemService(NotificationManager.class).notify(id, notification(title, views)); }

    private android.service.notification.StatusBarNotification active(int id) {
        for (android.service.notification.StatusBarNotification row : getSystemService(NotificationManager.class).getActiveNotifications()) if (row.getId() == id) return row;
        return null;
    }

    private void demo(String kind, int interval) {
        try {
            getSystemService(NotificationManager.class).cancelAll();
            RemoteViews views;
            if ("uri".equals(kind)) views = uriFrames(96);
            else if ("adapter".equals(kind)) views = adapter(150);
            else if ("property".equals(kind)) views = layout(R.layout.property_motion);
            else if ("draw".equals(kind)) {
                if (!supportsDrawInstructions()) throw new UnsupportedOperationException("DrawInstructions is unavailable on this Android build");
                views = RemoteDocument.views();
            }
            else views = frames(this, 120, interval);
            if (!"adapter".equals(kind) && !"property".equals(kind) && !"draw".equals(kind)) views.setInt(R.id.flip, "setFlipInterval", interval);
            post(707, "ViewFlipper lab · " + kind + " · " + interval + " ms", views);
            status.setText("Posted " + kind + " · " + interval + " ms · open notification shade");
        } catch (Throwable error) { status.setText(error.toString()); Log.e("FlipperLab", "Demo failed", error); }
    }

    public void onDestroy() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (host != null) host.stopListening();
        super.onDestroy();
    }
}
