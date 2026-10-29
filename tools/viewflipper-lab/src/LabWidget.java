package dev.noti.flipperlab;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public final class LabWidget extends AppWidgetProvider {
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, LabActivity.frames(context, 120, 33));
    }
}
