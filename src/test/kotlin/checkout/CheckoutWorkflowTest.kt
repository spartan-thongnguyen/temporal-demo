package checkout

import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.client.WorkflowUpdateException
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.Workflow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * Runs the real workflow and activities against Temporal's in-memory test
 * server. Time skipping makes the 3-day bank wait take milliseconds.
 */
class CheckoutWorkflowTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var world: FakeWorld

    @BeforeEach
    fun setUp() {
        env = newEnv()
        world = FakeWorld(dir.resolve("world.json").toString())
        val worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(CheckoutWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(CheckoutActivitiesImpl(world))
        env.start()
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `bank confirms - order ships, one charge even after a 503`() {
        val wf = start(Order(id = "1", fail503 = 1))
        wf.bankConfirmed()

        assertEquals("DONE", result(wf))
        val s = world.snapshot()
        assertEquals(listOf("CHARGED"), s.payments.values.map { it.status })
        assertEquals(1, s.shipments.size)
        assertEquals(1, s.emails.size)
    }

    @Test
    fun `bank never confirms - after 3 days the saga refunds and releases`() {
        val t0 = env.currentTimeMillis()
        val wf = start(Order(id = "2"))

        assertEquals("CANCELLED", result(wf))
        assertTrue(env.currentTimeMillis() - t0 >= Duration.ofDays(3).toMillis())
        val s = world.snapshot()
        assertEquals(listOf("REFUNDED"), s.payments.values.map { it.status })
        assertEquals(listOf("RELEASED"), s.reservations.values.map { it.status })
        assertEquals(0, s.shipments.size)
    }

    @Test
    fun `declined card - not retried, stock released, no money moved`() {
        val wf = start(Order(id = "3", card = "DECLINED"))

        assertEquals("PAYMENT_FAILED", result(wf))
        val s = world.snapshot()
        assertEquals(0, s.payments.size)
        assertEquals(listOf("RELEASED"), s.reservations.values.map { it.status })
    }

    @Test
    fun `query and update a running order`() {
        val wf = start(Order(id = "4"))
        waitForStatus(wf, "AWAITING_BANK")

        assertEquals("ship to: 1 Le Loi, District 1", wf.changeAddress("1 Le Loi, District 1"))
        assertThrows<WorkflowUpdateException> { wf.changeAddress(" ") }

        wf.bankConfirmed()
        assertEquals("DONE", result(wf))
        assertEquals("1 Le Loi, District 1", world.snapshot().shipments.values.single().address)
    }

    @Test
    fun `same idempotency key - the provider charges once`() {
        val first = world.charge(100, "4242", idempotencyKey = "order-5:7")
        val retry = world.charge(100, "4242", idempotencyKey = "order-5:7")

        assertEquals(first, retry)
        assertEquals(1, world.snapshot().payments.size)
    }

    /** Video, Part 5 · Versioning: replay real histories in CI. */
    @Test
    fun `replay - current code matches the history, an unsafe change does not`() {
        val wf = start(Order(id = "6"))
        wf.bankConfirmed()
        result(wf)
        val history = env.workflowClient.fetchHistory("order-6")

        replay(history, CheckoutWorkflowImpl::class.java)          // passes

        val e = assertThrows<Exception> { replay(history, CheckoutWorkflowV2Unsafe::class.java) }
        // The replayer reports it as text: "...NonDeterministicException: [TMPRL1100] ... 'NotifyCustomer'"
        assertTrue(e.message.orEmpty().contains("NonDeterministicException")) { "expected non-determinism, got $e" }
    }

    private fun newEnv() = TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareConverter()).build())
            .build()
    )

    private fun replay(history: io.temporal.common.WorkflowExecutionHistory, impl: Class<*>) {
        newEnv().use { replayEnv ->
            val worker = replayEnv.newWorker(TASK_QUEUE)
            worker.registerWorkflowImplementationTypes(impl)
            worker.replayWorkflowExecution(history)
        }
    }

    private fun start(order: Order): CheckoutWorkflow {
        val wf = env.workflowClient.newWorkflowStub(
            CheckoutWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("order-${order.id}").build()
        )
        WorkflowStub.fromTyped(wf).start(order)
        return wf
    }

    private fun result(wf: CheckoutWorkflow): String = WorkflowStub.fromTyped(wf).getResult(String::class.java)

    private fun waitForStatus(wf: CheckoutWorkflow, expected: String) {
        repeat(100) {
            if (wf.status() == expected) return
            Thread.sleep(50)
        }
        error("status never reached $expected, last: ${wf.status()}")
    }
}

/**
 * "v2" adds a step before the charge without Workflow.getVersion. New orders
 * would be fine, but a running order's history no longer matches the code.
 */
class CheckoutWorkflowV2Unsafe : CheckoutWorkflow {
    private val act = Workflow.newActivityStub(
        CheckoutActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
    )
    private var confirmed = false

    override fun bankConfirmed() {
        confirmed = true
    }

    override fun status() = "?"
    override fun changeAddress(address: String) = address
    override fun validateChangeAddress(address: String) {}

    override fun checkout(order: Order): String {
        val res = act.reserveInventory(order)
        act.notifyCustomer(order)                       // the new step, e.g. a fraud check
        val pay = act.chargePayment(order, res)
        val paid = Workflow.await(Duration.parse(order.bankWait)) { confirmed }
        if (paid) act.createShipment(order) else act.cancelPayment(pay)
        return if (paid) "DONE" else "CANCELLED"
    }
}
