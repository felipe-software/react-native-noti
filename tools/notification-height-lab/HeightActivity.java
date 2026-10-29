package dev.noti.heightlab;

import android.app.*;
import android.os.Bundle;
import android.graphics.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;

/** Static layout probe: uses the same MessagingStyle configuration as the live Doom image. */
public class HeightActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("height", "Height probe", NotificationManager.IMPORTANCE_LOW));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(16, 160, 16, 0);
        for (int[] size : new int[][]{{640,400},{1280,800},{1280,1280},{640,1600}}) {
            Button button = new Button(this);
            String name = size[0] + "x" + size[1];
            button.setText(name);
            button.setOnClickListener(v -> publish(size[0], size[1]));
            column.addView(button);
        }
        Button stop = new Button(this);
        stop.setText("Clear probe");
        stop.setOnClickListener(v -> manager.cancelAll());
        column.addView(stop);
        setContentView(column);
    }

    private void publish(int width, int height) {
        String name = width + "x" + height;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(28, 44, 60));
            Paint paint = new Paint();
            paint.setColor(Color.rgb(90, 120, 145));
            for (int x=0; x<width; x+=80) canvas.drawLine(x, 0, x, height, paint);
            for (int y=0; y<height; y+=80) canvas.drawLine(0, y, width, y, paint);
            paint.setColor(Color.GREEN);
            canvas.drawRect(0,0,width,16,paint);
            paint.setColor(Color.RED);
            canvas.drawRect(0,height-16,width,height,paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(40);
            canvas.drawText(name, 24, 64, paint);
            try (FileOutputStream file = openFileOutput(name + ".png", MODE_PRIVATE)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            }
            bitmap.recycle();
            Uri uri = Uri.parse("content://dev.noti.heightlab.images/" + name + ".png");
            Notification.MessagingStyle style = new Notification.MessagingStyle(new Person.Builder().setName("Player").build())
                .setConversationTitle("Height probe " + name)
                .addMessage(new Notification.MessagingStyle.Message("Height probe " + name, System.currentTimeMillis(),
                    new Person.Builder().setName("Freedoom").build()).setData("image/png", uri));
            Notification notification = new Notification.Builder(this, "height")
                .setSmallIcon(android.R.drawable.ic_media_play).setStyle(style)
                .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE).build();
            getSystemService(NotificationManager.class).notify(1, notification);
        } catch (IOException error) { throw new RuntimeException(error); }
    }
}
