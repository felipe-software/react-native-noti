package expo.modules.notificationmotion

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

class MotionWidgets private constructor(private val context: Context) {
  private val manager = AppWidgetManager.getInstance(context)
  private val component = ComponentName(context, MotionWidgetProvider::class.java)
  private val preferences = context.getSharedPreferences("notification-motion-widgets", Context.MODE_PRIVATE)
  private val images = MotionImages(context)

  private fun installed(): Boolean = manager.installedProviders.any { it.provider == component }

  private fun requireInstalled() {
    check(installed()) { "The Noti widget provider is not installed. Set widgets: true in the Expo plugin and rebuild." }
  }

  @Synchronized fun isPinningSupported(): Boolean = installed() && manager.isRequestPinAppWidgetSupported

  @Synchronized fun requestPin(serialized: String): Boolean {
    requireInstalled()
    if (!manager.isRequestPinAppWidgetSupported) return false
    val scene = JSONObject(serialized)
    preferences.edit().putString(DEFAULT_SCENE, scene.toString()).apply()
    val preview = renderer(PREVIEW_WIDGET_ID).render(scene)
    val extras = Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview) }
    val callback = Intent(context, MotionWidgetProvider::class.java)
      .setAction(MotionWidgetProvider.ACTION_PINNED)
      .setData(android.net.Uri.parse("notification-motion-widget://${context.packageName}/pinned"))
    val success = PendingIntent.getBroadcast(
      context,
      0,
      callback,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    return manager.requestPinAppWidget(component, extras, success)
  }

  @Synchronized fun list(): List<Int> {
    requireInstalled()
    return manager.getAppWidgetIds(component).toList()
  }

  @Synchronized fun update(widgetId: Int, serialized: String) {
    require(widgetId in manager.getAppWidgetIds(component)) { "Unknown widget id $widgetId." }
    val scene = JSONObject(serialized)
    val previous = stored(widgetId)
    save(widgetId, scene)
    publish(widgetId, scene, previous)
  }

  @Synchronized fun updateAll(serialized: String) {
    requireInstalled()
    val scene = JSONObject(serialized)
    preferences.edit().putString(DEFAULT_SCENE, scene.toString()).apply()
    manager.getAppWidgetIds(component).forEach { widgetId ->
      val previous = stored(widgetId)
      save(widgetId, scene)
      publish(widgetId, scene, previous)
    }
  }

  @Synchronized fun refresh(widgetIds: IntArray) {
    widgetIds.forEach { widgetId ->
      stored(widgetId)?.let { publish(widgetId, it, null) }
    }
  }

  @Synchronized fun pinned(widgetId: Int) {
    val scene = stored(widgetId) ?: return
    save(widgetId, scene)
    publish(widgetId, scene, null)
    NotificationMotionModule.dispatchWidgetPinned(widgetId)
  }

  @Synchronized fun delete(widgetIds: IntArray) {
    preferences.edit().also { editor ->
      widgetIds.forEach { editor.remove(key(it)) }
    }.apply()
  }

  @Synchronized fun restore(oldWidgetIds: IntArray, newWidgetIds: IntArray) {
    val editor = preferences.edit()
    oldWidgetIds.zip(newWidgetIds).forEach { (oldId, newId) ->
      preferences.getString(key(oldId), null)?.let { editor.putString(key(newId), it) }
      editor.remove(key(oldId))
    }
    editor.apply()
    refresh(newWidgetIds)
  }

  private fun publish(widgetId: Int, scene: JSONObject, previous: JSONObject?) {
    manager.updateAppWidget(widgetId, renderer(widgetId).render(scene, previous))
  }

  private fun renderer(widgetId: Int) = MotionRenderer(context, images, MotionTarget.Widget(widgetId))

  private fun stored(widgetId: Int): JSONObject? {
    val serialized = preferences.getString(key(widgetId), null)
      ?: preferences.getString(DEFAULT_SCENE, null)
      ?: return null
    return JSONObject(serialized)
  }

  private fun save(widgetId: Int, scene: JSONObject) {
    preferences.edit().putString(key(widgetId), scene.toString()).apply()
  }

  private fun key(widgetId: Int) = "widget-$widgetId"

  companion object {
    private const val DEFAULT_SCENE = "default-scene"
    private const val PREVIEW_WIDGET_ID = 0
    @Volatile private var instance: MotionWidgets? = null

    fun get(context: Context): MotionWidgets = instance ?: synchronized(this) {
      instance ?: MotionWidgets(context.applicationContext).also { instance = it }
    }
  }
}
