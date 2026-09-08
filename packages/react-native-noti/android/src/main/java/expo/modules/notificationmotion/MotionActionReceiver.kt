package expo.modules.notificationmotion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MotionActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.getStringExtra("motionAction") ?: return
    if (action == DISMISSED) {
      MotionNotifications.get(context).forget(
        intent.getIntExtra("notificationId", 0),
        intent.getStringExtra("notificationTag"),
      )
      return
    }
    if (intent.hasExtra("widgetId")) {
      NotificationMotionModule.dispatchWidget(
        intent.getIntExtra("widgetId", 0),
        intent.getStringExtra("nodeId").orEmpty(),
        action,
      )
      return
    }
    NotificationMotionModule.dispatch(
      intent.getIntExtra("notificationId", 0),
      intent.getStringExtra("notificationTag"),
      intent.getStringExtra("nodeId").orEmpty(),
      action,
    )
  }

  companion object {
    const val DISMISSED = "__notification_motion_dismissed"
  }
}
