package com.tgweb.backend.push

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class DeviceRegistration(
    val userId: Long,
    val deviceId: String,
    val fcmToken: String,
    val appVersion: String,
    val locale: String,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class DeviceUnregister(
    val deviceId: String,
)

@Serializable
data class PushAck(
    val deviceId: String,
    val messageId: String,
    val deliveredAtEpochMs: Long,
)

@Serializable
data class PushMetricEvent(
    val type: String,
    val reason: String? = null,
)

private val devices = ConcurrentHashMap<String, DeviceRegistration>()
private val acks = ConcurrentHashMap<String, PushAck>()
private val pushSent = AtomicLong(0)
private val pushDelivered = AtomicLong(0)
private val pushOpened = AtomicLong(0)
private val pushRetried = AtomicLong(0)
private val pushFailed = AtomicLong(0)
private val failureReasons = ConcurrentHashMap<String, AtomicLong>()

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        post("/v1/devices/register") {
            val body = call.receive<DeviceRegistration>()
            devices[body.deviceId] = body
            call.respond(HttpStatusCode.OK, mapOf("status" to "registered", "device_id" to body.deviceId))
        }

        post("/v1/devices/unregister") {
            val body = call.receive<DeviceUnregister>()
            devices.remove(body.deviceId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "unregistered", "device_id" to body.deviceId))
        }

        post("/v1/push/ack") {
            val body = call.receive<PushAck>()
            acks[body.messageId] = body
            pushDelivered.incrementAndGet()
            call.respond(HttpStatusCode.OK, mapOf("status" to "acknowledged", "message_id" to body.messageId))
        }

        post("/v1/push/metric") {
            val body = call.receive<PushMetricEvent>()
            when (body.type.lowercase()) {
                "sent" -> pushSent.incrementAndGet()
                "opened" -> pushOpened.incrementAndGet()
                "retried" -> pushRetried.incrementAndGet()
                "failed" -> {
                    pushFailed.incrementAndGet()
                    val reason = body.reason ?: "unknown"
                    failureReasons.computeIfAbsent(reason) { AtomicLong(0) }.incrementAndGet()
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "recorded"))
        }

        get("/v1/push/health") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "ok",
                    "time" to Instant.now().toString(),
                    "registered_devices" to devices.size,
                    "acks" to acks.size,
                )
            )
        }

        get("/v1/push/metrics") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "push_sent" to pushSent.get(),
                    "push_delivered" to pushDelivered.get(),
                    "push_opened" to pushOpened.get(),
                    "push_retried" to pushRetried.get(),
                    "push_failed" to pushFailed.get(),
                    "failure_reasons" to failureReasons.mapValues { it.value.get() },
                )
            )
        }
    }
}
