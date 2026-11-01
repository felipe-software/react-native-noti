package dev.reactnativenoti.deliverymap

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class DeliveryMapModule : Module() {
  private val context get() = requireNotNull(appContext.reactContext).applicationContext

  override fun definition() = ModuleDefinition {
    Name("DeliveryMap")
    AsyncFunction("start") { options: String -> DeliveryController.start(context, options) }
    AsyncFunction("stop") { DeliveryController.stop(context) }
    AsyncFunction("getStatus") { DeliveryController.status() }
  }
}
