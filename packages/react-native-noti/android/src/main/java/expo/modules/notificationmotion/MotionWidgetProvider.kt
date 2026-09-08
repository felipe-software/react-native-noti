package expo.modules.notificationmotion

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

class MotionWidgetProvider : AppWidgetProvider() {
  override fun onReceive(context: Context, intent: Intent) {
    val widgets = MotionWidgets.get(context)
    when (intent.action) {
      ACTION_PINNED -> async {
        val widgetId = intent.widgetId()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgets.pinned(widgetId)
      }
      AppWidgetManager.ACTION_APPWIDGET_UPDATE -> async {
        widgets.refresh(intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf())
      }
      AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> async {
        val widgetId = intent.widgetId()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgets.refresh(intArrayOf(widgetId))
      }
      AppWidgetManager.ACTION_APPWIDGET_DELETED -> async {
        val widgetId = intent.widgetId()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) widgets.delete(intArrayOf(widgetId))
      }
      AppWidgetManager.ACTION_APPWIDGET_RESTORED -> async {
        widgets.restore(
          intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: intArrayOf(),
          intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: intArrayOf(),
        )
      }
      else -> super.onReceive(context, intent)
    }
  }

  private fun Intent.widgetId() =
    getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

  private fun async(block: () -> Unit) {
    val pending = goAsync()
    executor.execute {
      try {
        block()
      } catch (error: Exception) {
        Log.e(TAG, "Unable to update the widget", error)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION_PINNED = "expo.modules.notificationmotion.WIDGET_PINNED"
    private const val TAG = "MotionWidgetProvider"
    private val executor = Executors.newSingleThreadExecutor()
  }
}
