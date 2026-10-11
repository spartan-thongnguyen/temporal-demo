package checkout

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.failure.ApplicationFailure
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Video, Part 2 · Client. The API talks to workflows only through the Temporal
 * Service. No outbox, no status table, no webhook consumer.
 */
@RestController
class CheckoutController(private val client: WorkflowClient, private val world: FakeWorld) {

    @PostMapping("/checkout")
    fun checkout(@RequestBody o: Order): Map<String, Any> {
        val wf = client.newWorkflowStub(
            CheckoutWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE)
                .setWorkflowId("order-${o.id}").build()              // business key
        )
        val started = try {
            WorkflowStub.fromTyped(wf).start(o)                       // durable, returns at once
            true
        } catch (e: WorkflowExecutionAlreadyStarted) {                // a retried POST
            false
        }
        return mapOf("workflowId" to "order-${o.id}", "started" to started)
    }

    /** The bank webhook becomes a signal: a method call on the running workflow. */
    @PostMapping("/webhooks/bank")
    fun bank(@RequestBody e: BankEvent): Map<String, String> {
        client.newWorkflowStub(CheckoutWorkflow::class.java, "order-${e.orderId}").bankConfirmed()
        return mapOf("signal" to "bankConfirmed", "workflowId" to "order-${e.orderId}")
    }

    /** The status endpoint becomes a query, answered by the workflow itself. */
    @GetMapping("/orders/{id}/status")
    fun status(@PathVariable id: String): Map<String, String> =
        mapOf("workflowId" to "order-$id", "status" to stub(id).status())

    /** An update: validated by the workflow, then applied, with a result. */
    @PutMapping("/orders/{id}/address")
    fun changeAddress(@PathVariable id: String, @RequestBody body: AddressChange): Map<String, String> =
        mapOf("workflowId" to "order-$id", "result" to stub(id).changeAddress(body.address))

    /** What the fake outside world looks like now: count the charges. */
    @GetMapping("/world")
    fun world(): FakeWorld.State = world.snapshot()

    private fun stub(id: String) = client.newWorkflowStub(CheckoutWorkflow::class.java, "order-$id")

    @ExceptionHandler(WorkflowNotFoundException::class)
    fun notFound(e: WorkflowNotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "no running order workflow (unknown id, or already finished)"))

    /** The validator said no: return its message, e.g. "too late: order is SHIPPING". */
    @ExceptionHandler(WorkflowUpdateException::class)
    fun rejected(e: WorkflowUpdateException): ResponseEntity<Map<String, String>> {
        val reason = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<ApplicationFailure>().lastOrNull()?.originalMessage
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to (reason ?: "update rejected")))
    }
}
