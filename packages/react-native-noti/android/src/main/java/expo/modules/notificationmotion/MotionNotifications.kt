package expo.modules.notificationmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import org.json.JSONObject

internal class MotionNotifications private constructor(private val context: Context) {
  private data class Key(val id: Int, val tag: String?) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "tag" to tag)
  }
  private data class Entry(val base: Notification, val scene: JSONObject, val adopted: Boolean)
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val preferences = context.getSharedPreferences("notification-motion", Context.MODE_PRIVATE)
  private val entries = mutableMapOf<Key, Entry>()
  private val images = MotionImages(context)

  private fun supported() {
    check(Build.VERSION.SDK_INT >= 31) { "Noti requires Android 12 / API 31+." }
  }
  private fun enabled(channelId: String) {
    check(manager.areNotificationsEnabled()) { "Notification permission is disabled. Call requestPermission() first." }
    check(manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE) { "This notification channel is disabled." }
  }
  private fun key(identity: NotificationIdentity) = Key(identity.id, identity.tag)
  private fun active(key: Key): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.id == key.id && it.tag == key.tag }

  @Synchronized fun create(serialized: String): Map<String, Any?> {
    supported()
    val scene = JSONObject(serialized)
    require(scene.has("collapsed")) { "A collapsed scene is required." }
    val channelId = scene.optString("channelId", "notification-motion")
    val importance = when (scene.optString("importance", "low")) {
      "high" -> NotificationManager.IMPORTANCE_HIGH
      "default" -> NotificationManager.IMPORTANCE_DEFAULT
      else -> NotificationManager.IMPORTANCE_LOW
    }
    manager.createNotificationChannel(NotificationChannel(channelId, scene.optString("channelName", "Animated notifications"), importance))
    enabled(channelId)
    val requestedId = if (scene.has("id")) scene.getInt("id") else null
    val requestedTag = if (scene.has("tag") && !scene.isNull("tag")) scene.getString("tag") else null
    val activeKeys = manager.activeNotifications.map { Key(it.id, it.tag) }.toSet()
    entries.keys.retainAll(activeKeys)
    var id = requestedId ?: preferences.getInt("nextId", 1_000_000)
    if (requestedId == null) {
      while (Key(id, null) in activeKeys) id = if (id == Int.MAX_VALUE) 1_000_000 else id + 1
      preferences.edit().putInt("nextId", if (id == Int.MAX_VALUE) 1_000_000 else id + 1).apply()
    }
    val key = Key(id, requestedTag)
    check(key in entries || entries.size < 32) { "At most 32 managed notifications may be active." }
    val icon = context.resources.getIdentifier(scene.optString("smallIcon", "nm_small_icon"), "drawable", context.packageName)
    require(icon != 0) { "smallIcon must name an existing Android drawable." }
    val builder = Notification.Builder(context, channelId)
      .setSmallIcon(icon)
      .setContentTitle(scene.getString("title"))
      .setContentText(scene.optString("body"))
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setOngoing(scene.optBoolean("ongoing", false))
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
      builder.setContentIntent(PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
    val entry = Entry(builder.build(), scene, false)
    publish(key, entry, null)
    entries[key] = entry
    return key.toMap()
  }

  @Synchronized fun adopt(identity: NotificationIdentity): Map<String, Any?> {
    supported()
    val key = key(identity)
    val notification = requireNotNull(active(key)) { "No active notification with this (id, tag) belongs to this app." }
    entries.getOrPut(key) { Entry(notification.notification.clone(), JSONObject(), true) }
    return key.toMap()
  }

  @Synchronized fun update(identity: NotificationIdentity, serialized: String) {
    supported()
    val key = key(identity)
    val entry = requireNotNull(entries[key]) { "Unknown notification. Call adopt() to take over an existing notification." }
    val scene = JSONObject(entry.scene.toString())
    val patch = JSONObject(serialized)
    patch.keys().forEach { name -> scene.put(name, patch.get(name)) }
    if (scene.toString() == entry.scene.toString()) return
    val next = Entry(entry.base, scene, entry.adopted)
    publish(key, next, entry.scene)
    entries[key] = next
  }

  private fun publish(key: Key, entry: Entry, previous: JSONObject?) {
    enabled(entry.base.channelId)
    val cleanBase = entry.base.clone().also {
      if (!entry.adopted || entry.scene.has("actions")) it.actions = null
    }
    val builder = Notification.Builder.recoverBuilder(context, cleanBase)
      .setStyle(Notification.DecoratedCustomViewStyle())
      .setOnlyAlertOnce(true)
      .setDeleteIntent(deleteIntent(key))
    val renderer = MotionRenderer(context, images, MotionTarget.Notification(key.id, key.tag))
    if (entry.scene.has("title")) builder.setContentTitle(entry.scene.getString("title"))
    if (entry.scene.has("body")) builder.setContentText(entry.scene.getString("body"))
    if (entry.scene.has("actions")) {
      val actions = entry.scene.getJSONArray("actions")
      require(actions.length() <= 3) { "Android notifications support at most three actions." }
      for (index in 0 until actions.length()) {
        val action = actions.getJSONObject(index)
        builder.addAction(
          Notification.Action.Builder(
            null as Icon?,
            action.getString("title"),
            actionIntent(key, action.getString("id"), action.getString("onPress")),
          ).build(),
        )
      }
    }
    entry.scene.optJSONObject("collapsed")?.let { builder.setCustomContentView(renderer.render(it, previous?.optJSONObject("collapsed"))) }
    if (entry.scene.has("expanded")) builder.setCustomBigContentView(entry.scene.optJSONObject("expanded")?.let { renderer.render(it, previous?.optJSONObject("expanded")) })
    if (entry.scene.has("headsUp")) builder.setCustomHeadsUpContentView(entry.scene.optJSONObject("headsUp")?.let { renderer.render(it, previous?.optJSONObject("headsUp")) })
    manager.notify(key.tag, key.id, builder.build())
  }

  @Synchronized fun listActive(): List<Map<String, Any?>> {
    supported()
    return manager.activeNotifications.map {
      mapOf("id" to it.id, "tag" to it.tag, "title" to it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        "body" to it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(), "channelId" to it.notification.channelId,
        "managed" to entries.containsKey(Key(it.id, it.tag)))
    }
  }

  @Synchronized fun dismiss(identity: NotificationIdentity) {
    supported()
    val key = key(identity)
    require(entries.containsKey(key)) { "Adopt the notification before dismissing it through this library." }
    manager.cancel(key.tag, key.id)
    entries.remove(key)
  }

  @Synchronized fun forget(id: Int, tag: String?) {
    entries.remove(Key(id, tag))
  }

  private fun deleteIntent(key: Key): PendingIntent {
    val uri = Uri.Builder().scheme("notification-motion-dismiss")
      .authority(context.packageName).appendPath(key.id.toString()).appendPath(key.tag ?: "").build()
    val intent = Intent(context, MotionActionReceiver::class.java).setData(uri)
      .putExtra("notificationId", key.id).putExtra("notificationTag", key.tag)
      .putExtra("motionAction", MotionActionReceiver.DISMISSED)
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun actionIntent(key: Key, nodeId: String, action: String): PendingIntent {
    val uri = Uri.Builder().scheme("notification-motion-action").authority(context.packageName)
      .appendPath(key.id.toString()).appendPath(key.tag ?: "")
      .appendPath(nodeId).appendPath(action).build()
    val intent = Intent(context, MotionActionReceiver::class.java).setData(uri)
      .putExtra("notificationId", key.id).putExtra("notificationTag", key.tag)
      .putExtra("nodeId", nodeId).putExtra("motionAction", action)
    return PendingIntent.getBroadcast(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  @Synchronized fun scrollTo(identity: NotificationIdentity, nodeId: String, index: Int) {
    supported()
    val scene = JSONObject(requireNotNull(entries[key(identity)]) { "Unknown notification." }.scene.toString())
    var found = false
    fun visit(node: JSONObject) {
      if (node.optString("type") == "scroll" && node.optString("id") == nodeId) {
        val count = node.getJSONArray("children").length()
        require(index in 0..maxOf(0, count - node.getInt("windowSize"))) { "Scroll index outside the content window." }
        node.put("index", index)
        found = true
      }
      val children = node.optJSONArray("children")
      if (children != null) (0 until children.length()).forEach { visit(children.getJSONObject(it)) }
    }
    scene.optJSONObject("collapsed")?.let(::visit)
    scene.optJSONObject("expanded")?.let(::visit)
    scene.optJSONObject("headsUp")?.let(::visit)
    require(found) { "No ScrollView with id $nodeId exists in this notification." }
    update(identity, scene.toString())
  }

  companion object {
    @Volatile private var instance: MotionNotifications? = null
    fun get(context: Context): MotionNotifications = instance ?: synchronized(this) {
      instance ?: MotionNotifications(context.applicationContext).also { instance = it }
    }
  }
}
