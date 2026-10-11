package checkout

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * One process plays two roles from the video: the API (client) and the worker.
 * The Temporal starter reads application.yml, connects to the service, and
 * starts a worker on the "checkout" task queue for the @WorkflowImpl and
 * @ActivityImpl classes it finds.
 */
@SpringBootApplication
class CheckoutApplication {

    /**
     * The default Temporal JSON converter cannot build a Kotlin data class that
     * has no no-argument constructor. The Jackson Kotlin module fixes that. The
     * starter picks up this bean for both the client and the worker.
     */
    @Bean
    fun dataConverter(): DataConverter = kotlinAwareConverter()
}

fun kotlinAwareConverter(): DataConverter =
    DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
        JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule())
    )

fun main(args: Array<String>) {
    runApplication<CheckoutApplication>(*args)
}
