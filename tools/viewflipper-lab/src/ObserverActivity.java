package dev.noti.flipperlab;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.widget.TextView;

public final class ObserverActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        NotificationListenerService.requestRebind(new ComponentName(this, LabListener.class));
        TextView status = new TextView(this);
        status.setText("ViewFlipper observer\n\nRecords only notifications from dev.noti.flipperlab.\n\nOpen the lab's rates experiment after enabling this observer.");
        status.setTextSize(18);
        status.setPadding(32, 80, 32, 32);
        setContentView(status);
    }
}
