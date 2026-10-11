package checkout

import io.temporal.activity.ActivityInterface

/**
 * Video, Part 2 · Activity: where side effects live. Each one runs at least
 * once, so each one is idempotent.
 */
@ActivityInterface
interface CheckoutActivities {
    fun reserveInventory(order: Order): String
    fun chargePayment(order: Order, resId: String): String
    fun createShipment(order: Order): String
    fun notifyCustomer(order: Order)
    fun cancelPayment(payId: String)
    fun releaseInventory(resId: String)
}
