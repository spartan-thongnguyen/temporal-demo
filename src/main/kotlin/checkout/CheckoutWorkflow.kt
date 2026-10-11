package checkout

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Video, Part 2 · Workflow: one workflow method runs the flow, a signal pushes
 * data in, a query reads state out. Part 4 adds an update.
 */
@WorkflowInterface
interface CheckoutWorkflow {

    @WorkflowMethod
    fun checkout(order: Order): String

    /** The bank webhook becomes a signal. */
    @SignalMethod
    fun bankConfirmed()

    /** The status endpoint becomes a query. It never changes the history. */
    @QueryMethod
    fun status(): String

    /** Validate, change state, and return a result to the caller. */
    @UpdateMethod
    fun changeAddress(address: String): String

    @UpdateValidatorMethod(updateName = "changeAddress")
    fun validateChangeAddress(address: String)
}
