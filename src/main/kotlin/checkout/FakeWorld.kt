package checkout

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

class CardDeclinedException(message: String) : RuntimeException(message)

/**
 * Stand-ins for the systems the checkout talks to: inventory, a payment
 * provider, a carrier and an email service. Like the real ones, they live
 * outside the worker: their state is saved to a file on every change, so it
 * survives when you kill the app.
 *
 * Each write takes an idempotency key. A repeated key returns the first result
 * and changes nothing. That is how a retried activity avoids a double charge.
 */
@Component
class FakeWorld(@Value("\${checkout.world-file}") path: String) {

    data class Reservation(val id: String, val item: String, var status: String)
    data class Payment(val id: String, val amount: Long, var status: String)
    data class Shipment(val id: String, val item: String, val address: String)

    data class State(
        val reservations: MutableMap<String, Reservation> = linkedMapOf(),
        val payments: MutableMap<String, Payment> = linkedMapOf(),
        val shipments: MutableMap<String, Shipment> = linkedMapOf(),
        val emails: MutableList<String> = mutableListOf(),
        val keys: MutableMap<String, String> = linkedMapOf()   // idempotency key -> first result
    )

    private val file = File(path)
    private val json = jacksonObjectMapper()
    private val state: State = if (file.exists()) json.readValue(file) else State()

    @Synchronized
    fun reserve(item: String, idempotencyKey: String): String = once(idempotencyKey, "inventory") {
        val id = "res-${state.reservations.size + 1}"
        state.reservations[id] = Reservation(id, item, "HELD")
        log.info(">>> INVENTORY  reserved 1 x {} as {}", item, id)
        id
    }

    @Synchronized
    fun charge(amount: Long, card: String, idempotencyKey: String): String = once(idempotencyKey, "payments") {
        if (card == "DECLINED") {
            log.info(">>> PAYMENTS   card declined, no money moved")
            throw CardDeclinedException("card declined by issuer")
        }
        val id = "pay-${state.payments.size + 1}"
        state.payments[id] = Payment(id, amount, "CHARGED")
        log.info(">>> PAYMENTS   charged {} cents as {}   (total charges so far: {})", amount, id, state.payments.size)
        id
    }

    @Synchronized
    fun refund(payId: String) {
        val p = state.payments.getValue(payId)
        if (p.status == "REFUNDED") return
        p.status = "REFUNDED"
        save()
        log.info(">>> PAYMENTS   refunded {}", payId)
    }

    @Synchronized
    fun release(resId: String) {
        val r = state.reservations.getValue(resId)
        if (r.status == "RELEASED") return
        r.status = "RELEASED"
        save()
        log.info(">>> INVENTORY  released {}", resId)
    }

    @Synchronized
    fun ship(item: String, address: String, idempotencyKey: String): String = once(idempotencyKey, "carrier") {
        val id = "ship-${state.shipments.size + 1}"
        state.shipments[id] = Shipment(id, item, address)
        log.info(">>> CARRIER    shipment {} to '{}'", id, address)
        id
    }

    @Synchronized
    fun email(text: String, idempotencyKey: String) {
        once(idempotencyKey, "email") {
            state.emails += text
            log.info(">>> EMAIL      \"{}\"", text)
            "sent"
        }
    }

    @Synchronized
    fun snapshot(): State = json.readValue(json.writeValueAsBytes(state))

    private fun once(key: String, system: String, write: () -> String): String {
        state.keys[key]?.let {
            log.info(">>> {} key {} seen before -> returning {}, nothing new happened", system.uppercase().padEnd(10), key, it)
            return it
        }
        val result = write()
        state.keys[key] = result
        save()
        return result
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        json.writerWithDefaultPrettyPrinter().writeValue(tmp, state)
        tmp.renameTo(file)
    }

    companion object {
        private val log = LoggerFactory.getLogger(FakeWorld::class.java)
    }
}
