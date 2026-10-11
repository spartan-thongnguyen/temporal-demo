package checkout

import io.temporal.activity.Activity
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.ActivityImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Video, Part 2 · Activity and Part 3 · Idempotency. Normal Spring code that
 * talks to the outside world (here: FakeWorld). Every call that changes the
 * world sends a key that stays the same on every retry: workflowId:activityId.
 */
@Component
@ActivityImpl(taskQueues = [TASK_QUEUE])
class CheckoutActivitiesImpl(private val world: FakeWorld) : CheckoutActivities {

    override fun reserveInventory(order: Order): String =
        world.reserve(order.item, idempotencyKey = key())

    override fun chargePayment(order: Order, resId: String): String {
        val info = Activity.getExecutionContext().info
        val key = "${info.workflowId}:${info.activityId}"          // same on every retry
        log.info(">>> chargePayment attempt {} key={}", info.attempt, key)

        // Demo fault: the provider is down for the first N attempts. A plain
        // exception is retryable, so Temporal retries with backoff.
        if (info.attempt <= order.fail503) {
            throw IllegalStateException("payment provider: 503 Service Unavailable")
        }

        val payId = try {
            world.charge(order.total, order.card, idempotencyKey = key)
        } catch (e: CardDeclinedException) {
            // A business answer, not an outage: do not retry, let the workflow decide.
            throw ApplicationFailure.newNonRetryableFailure(e.message, "Declined")
        }

        // Demo fault: the pod dies after the money moved, before Temporal hears
        // about it. After the restart Temporal retries with the same key.
        if (order.crashAfterCharge && info.attempt == 1) {
            log.warn(">>> CRASH: charged {} but the worker dies before reporting. Restart the app.", payId)
            Runtime.getRuntime().halt(137)
        }
        return payId
    }

    override fun createShipment(order: Order): String =
        world.ship(order.item, order.address, idempotencyKey = key())

    override fun notifyCustomer(order: Order) =
        world.email("Your ${order.item} is on its way to ${order.address}", idempotencyKey = key())

    override fun cancelPayment(payId: String) = world.refund(payId)

    override fun releaseInventory(resId: String) = world.release(resId)

    private fun key(): String {
        val info = Activity.getExecutionContext().info
        return "${info.workflowId}:${info.activityId}"
    }

    companion object {
        private val log = LoggerFactory.getLogger(CheckoutActivitiesImpl::class.java)
    }
}
