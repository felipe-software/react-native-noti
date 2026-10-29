package dev.noti.flipperlab;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public final class LabListener extends NotificationListenerService {
    public void onListenerConnected() {
        try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "connected.txt"))) {
            stream.write(Long.toString(SystemClock.uptimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) { android.util.Log.e("FlipperObserver", "Cannot record connection", error); }
    }

    public void onNotificationPosted(StatusBarNotification row) {
        if (!LabActivity.PKG.equals(row.getPackageName())) return;
        try {
            JSONObject event = new JSONObject();
            event.put("id", row.getId());
            event.put("sequence", row.getNotification().extras.getInt("lab_sequence", -1));
            event.put("sentUptimeMs", row.getNotification().extras.getLong("lab_sent_uptime", -1));
            event.put("receivedUptimeMs", SystemClock.uptimeMillis());
            try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), "listener.jsonl"), true)) {
                stream.write((event.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception error) { android.util.Log.e("FlipperObserver", "Cannot record event", error); }
    }
}
