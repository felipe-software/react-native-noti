package expo.modules.notificationmotion

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class NotificationIdentity : Record {
  @Field val id: Int = 0
  @Field val tag: String? = null
}

class NotificationMotionModule : Module() {
  private val context get() = requireNotNull(appContext.reactContext).applicationContext
  private val store get() = MotionNotifications.get(context)
  private val widgets get() = MotionWidgets.get(context)

  override fun definition() = ModuleDefinition {
    Name("NotificationMotion")
    Events("onAction", "onWidgetAction", "onWidgetPinned")
    AsyncFunction("create") { options: String -> store.create(options) }
    AsyncFunction("update") { identity: NotificationIdentity, update: String -> store.update(identity, update) }
    AsyncFunction("adopt") { identity: NotificationIdentity -> store.adopt(identity) }
    AsyncFunction("listActive") { store.listActive() }
    AsyncFunction("dismiss") { identity: NotificationIdentity -> store.dismiss(identity) }
    AsyncFunction("scrollTo") { identity: NotificationIdentity, nodeId: String, index: Int -> store.scrollTo(identity, nodeId, index) }
    AsyncFunction("playFrames") { options: String -> FramePlaybackController.play(context, options) }
    AsyncFunction("stopFrames") { FramePlaybackController.stop(context) }
    AsyncFunction("getFramesStatus") { FramePlaybackController.status(context) }
    AsyncFunction("isWidgetPinningSupported") { widgets.isPinningSupported() }
    AsyncFunction("requestPinWidget") { scene: String -> widgets.requestPin(scene) }
    AsyncFunction("listWidgets") { widgets.list() }
    AsyncFunction("updateWidget") { widgetId: Int, scene: String -> widgets.update(widgetId, scene) }
    AsyncFunction("updateAllWidgets") { scene: String -> widgets.updateAll(scene) }
    OnCreate { active = this@NotificationMotionModule }
    OnDestroy { if (active === this@NotificationMotionModule) active = null }
  }

  companion object {
    @Volatile private var active: NotificationMotionModule? = null
    fun dispatch(id: Int, tag: String?, nodeId: String, action: String) {
      active?.sendEvent("onAction", mapOf("id" to id, "tag" to tag, "nodeId" to nodeId, "action" to action))
    }
    fun dispatchWidget(widgetId: Int, nodeId: String, action: String) {
      active?.sendEvent("onWidgetAction", mapOf("widgetId" to widgetId, "nodeId" to nodeId, "action" to action))
    }
    fun dispatchWidgetPinned(widgetId: Int) {
      active?.sendEvent("onWidgetPinned", mapOf("widgetId" to widgetId))
    }
  }
}
