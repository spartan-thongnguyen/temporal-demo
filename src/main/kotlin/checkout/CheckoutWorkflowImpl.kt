package checkout

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Workflow
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Video, Part 2 · Workflow and Part 4 · Saga.
 *
 * State lives in plain fields (no status column). Steps are plain calls (the
 * reservation ID and payment ID are local variables). The 3-day wait is one
 * line. Compensation is try/catch. This code must stay deterministic: no clock,
 * no random, no I/O. All of that lives in CheckoutActivities.
 */
@WorkflowImpl(taskQueues = [TASK_QUEUE])
class CheckoutWorkflowImpl : CheckoutWorkflow {

    private val act = Workflow.newActivityStub(CheckoutActivities::class.java, OPTIONS)

    private var status = "STARTED"
    private var confirmed = false
    private var address = ""

    override fun bankConfirmed() {
        confirmed = true
    }

    override fun status() = status

    override fun validateChangeAddress(address: String) {
        require(address.isNotBlank()) { "address is blank" }
        require(status in setOf("STARTED", "RESERVED", "AWAITING_BANK")) { "too late: order is $status" }
    }

    override fun changeAddress(address: String): String {
        this.address = address
        trace("address changed to '$address'")
        return "ship to: $address"
    }

    override fun checkout(order: Order): String {
        address = order.address

        val res = act.reserveInventory(order)
        status = "RESERVED"
        trace("reserveInventory -> $res")

        val pay = try {
            act.chargePayment(order, res)
        } catch (e: ActivityFailure) {
            trace("chargePayment failed (${e.cause?.message}) -> compensate")
            act.releaseInventory(res)                       // undo step 1
            status = "PAYMENT_FAILED"
            return status
        }
        status = "AWAITING_BANK"
        trace("chargePayment -> $pay, waiting up to ${order.bankWait} for the bank")

        val paid = Workflow.await(Duration.parse(order.bankWait)) { confirmed }

        if (!paid) {
            trace("bank never confirmed -> compensate")
            act.cancelPayment(pay)                          // undo step 2
            act.releaseInventory(res)                       // undo step 1
        } else {
            status = "SHIPPING"
            trace("bank confirmed -> ship")
            act.createShipment(order.copy(address = address))
            act.notifyCustomer(order)
        }
        status = if (paid) "DONE" else "CANCELLED"
        trace("finished: $status")
        return status
    }

    /**
     * Prints each decision, marked LIVE or REPLAY. After a worker restart you
     * see the old steps come back as REPLAY: answered from the event history,
     * not called again. (Workflow.getLogger hides replay lines; this one shows
     * them on purpose. A log line is safe here because it changes no decision.)
     */
    private fun trace(msg: String) {
        val mode = if (Workflow.isReplaying()) "REPLAY" else "LIVE  "
        log.info("[workflow {}] {} | {}", Workflow.getInfo().workflowId, mode, msg)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CheckoutWorkflowImpl::class.java)

        /** Video, Part 2 · Activity: the timeout and retry policy are configuration. */
        private val OPTIONS: ActivityOptions = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(20).build())
            .build()
    }
}
