package checkout

/** The one task queue that the worker polls and the client starts workflows on. */
const val TASK_QUEUE = "checkout"

/**
 * The order the customer submits. The last four fields exist only for the demo:
 * they let one curl call pick which failure from the video to show.
 */
data class Order(
    val id: String,
    val item: String = "mechanical-keyboard",
    val total: Long = 129_00,                 // cents
    val address: String = "12 Nguyen Hue, District 1, HCMC",
    val card: String = "4242",                // "DECLINED" -> the bank says no (non-retryable)
    val bankWait: String = "P3D",             // ISO-8601. "PT30S" shows the timeout path in 30 s
    val fail503: Int = 0,                     // the payment provider answers 503 on the first N attempts
    val crashAfterCharge: Boolean = false     // kill the JVM after the charge, before reporting it
)

/** Body of the bank webhook: "the transfer for this order cleared". */
data class BankEvent(val orderId: String)

/** Body of the change-address update. */
data class AddressChange(val address: String)
