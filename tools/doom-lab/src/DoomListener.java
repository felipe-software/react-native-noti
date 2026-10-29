package dev.noti.doomobserver;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;

public final class DoomListener extends NotificationListenerService {
    public void onListenerConnected() {
        try (FileWriter file = new FileWriter(new File(getFilesDir(), "connected.txt"))) {
            file.write(Long.toString(SystemClock.uptimeMillis()));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    public void onNotificationPosted(StatusBarNotification item) {
        if (!"dev.noti.doomlab".equals(item.getPackageName())) return;
        Bundle extras = item.getNotification().extras;
        int sequence = extras.getInt("doomlabSequence", 0);
        if (sequence == 0) return;
        try (FileWriter file = new FileWriter(new File(getFilesDir(), "posted.jsonl"), true)) {
            JSONObject row = new JSONObject().put("sequence", sequence).put("id", item.getId()).put("sentUptimeMs", extras.getLong("doomlabSentUptime"))
                .put("receivedUptimeMs", SystemClock.uptimeMillis());
            file.write(row.toString() + "\n");
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static final class Launcher extends Activity {
        public void onCreate(Bundle state) {
            super.onCreate(state);
            requestRebind(new ComponentName(this, DoomListener.class));
            TextView label = new TextView(this);
            label.setText("Doom lab observer\nOnly the temporary Doom lab's notifications are recorded.");
            label.setTextSize(20); label.setPadding(32, 96, 32, 32); setContentView(label);
        }
    }
}
