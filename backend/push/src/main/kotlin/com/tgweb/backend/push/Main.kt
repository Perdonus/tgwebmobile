package com.tgweb.backend.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val HEADER_SHARED_SECRET = "X-FlyGram-Key"
private const val DEFAULT_SHARED_SECRET = "flygram_push_2026"
private const val DEFAULT_FCM_PROJECT_ID = "com-tgweb-app"
private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

private const val ENV_BIND_HOST = "PUSH_BIND_HOST"
private const val ENV_BIND_PORT = "PUSH_PORT"
private const val ENV_BASE_PATH = "PUSH_BASE_PATH"
private const val ENV_SHARED_SECRET = "PUSH_SHARED_SECRET"
private const val ENV_FCM_PROJECT_ID = "FCM_PROJECT_ID"
private const val ENV_FCM_SERVICE_ACCOUNT_JSON = "FCM_SERVICE_ACCOUNT_JSON"
private const val ENV_FIREBASE_GOOGLE_SERVICES_JSON = "FIREBASE_GOOGLE_SERVICES_JSON"
private const val ENV_FIREBASE_ADMIN_SDK_JSON = "FIREBASE_ADMIN_SDK_JSON"

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

@Serializable
data class PushSendRequest(
    val userId: Long? = null,
    val deviceIds: List<String> = emptyList(),
    val fcmTokens: List<String> = emptyList(),
    val data: Map<String, String> = emptyMap(),
    val priority: String = "high",
    val ttlSeconds: Long? = 60L,
    val collapseKey: String? = null,
)

private val devices = ConcurrentHashMap<String, DeviceRegistration>()
private val acks = ConcurrentHashMap<String, PushAck>()
private val pushSent = AtomicLong(0)
private val pushDelivered = AtomicLong(0)
private val pushOpened = AtomicLong(0)
private val pushRetried = AtomicLong(0)
private val pushFailed = AtomicLong(0)
private val failureReasons = ConcurrentHashMap<String, AtomicLong>()

private val bindHost = System.getenv(ENV_BIND_HOST)?.trim().orEmpty().ifBlank { "192.168.1.109" }
private val bindPort = System.getenv(ENV_BIND_PORT)?.toIntOrNull() ?: 8081
private val apiBasePath = normalizeBasePath(System.getenv(ENV_BASE_PATH)?.trim().orEmpty().ifBlank { "/flygram/push" })
private val sharedSecret = System.getenv(ENV_SHARED_SECRET)?.trim().orEmpty().ifBlank { DEFAULT_SHARED_SECRET }
private val googleServicesJsonPath = System.getenv(ENV_FIREBASE_GOOGLE_SERVICES_JSON)?.trim().orEmpty()
    .ifBlank { "/root/tgweb/app/google-services.json" }
private val adminSdkJsonPath = System.getenv(ENV_FIREBASE_ADMIN_SDK_JSON)?.trim().orEmpty()
    .ifBlank { System.getenv(ENV_FCM_SERVICE_ACCOUNT_JSON)?.trim().orEmpty() }

fun main() {
    embeddedServer(
        Netty,
        port = bindPort,
        host = bindHost,
        module = Application::module,
    ).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        registerPushRoutes(pathPrefix = "")
        if (apiBasePath != "/") {
            route(apiBasePath) {
                registerPushRoutes(pathPrefix = apiBasePath)
            }
        }
    }
}

private fun Route.registerPushRoutes(pathPrefix: String) {
    post("/v1/devices/register") {
        if (!call.requireAuthorized()) return@post

        val body = call.receive<DeviceRegistration>()
        devices[body.deviceId] = body
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive("registered"))
                put("device_id", JsonPrimitive(body.deviceId))
            },
        )
    }

    post("/v1/devices/unregister") {
        if (!call.requireAuthorized()) return@post

        val body = call.receive<DeviceUnregister>()
        devices.remove(body.deviceId)
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive("unregistered"))
                put("device_id", JsonPrimitive(body.deviceId))
            },
        )
    }

    post("/v1/push/ack") {
        if (!call.requireAuthorized()) return@post

        val body = call.receive<PushAck>()
        acks[body.messageId] = body
        pushDelivered.incrementAndGet()
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive("acknowledged"))
                put("message_id", JsonPrimitive(body.messageId))
            },
        )
    }

    post("/v1/push/metric") {
        if (!call.requireAuthorized()) return@post

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
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive("recorded"))
            },
        )
    }

    post("/v1/push/send") {
        if (!call.requireAuthorized()) return@post

        val body = call.receive<PushSendRequest>()
        val targets = resolveTargetDevices(body)
        if (targets.isEmpty()) {
            call.respond(
                HttpStatusCode.NotFound,
                buildJsonObject {
                    put("status", JsonPrimitive("no_targets"))
                    put(
                        "requested_device_ids",
                        buildJsonArray {
                            body.deviceIds.forEach { deviceId ->
                                add(JsonPrimitive(deviceId))
                            }
                        },
                    )
                    put("requested_fcm_tokens_count", JsonPrimitive(body.fcmTokens.count { it.isNotBlank() }))
                    body.userId?.let { put("user_id", JsonPrimitive(it)) }
                },
            )
            return@post
        }

        var sent = 0
        var failed = 0
        val failures = mutableListOf<Map<String, String>>()

        targets.forEach { registration ->
            val sendResult = FcmGateway.sendData(
                token = registration.fcmToken,
                data = body.data,
                priority = body.priority,
                ttlSeconds = body.ttlSeconds,
                collapseKey = body.collapseKey,
            )
            if (sendResult.isSuccess) {
                sent += 1
                pushSent.incrementAndGet()
            } else {
                failed += 1
                pushFailed.incrementAndGet()
                val reason = sendResult.exceptionOrNull()?.message.orEmpty().ifBlank { "send_failed" }
                failureReasons.computeIfAbsent(reason) { AtomicLong(0) }.incrementAndGet()
                failures += mapOf(
                    "device_id" to registration.deviceId,
                    "reason" to reason,
                )
            }
        }

        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive(if (failed == 0) "ok" else "partial"))
                put("targeted", JsonPrimitive(targets.size))
                put("sent", JsonPrimitive(sent))
                put("failed", JsonPrimitive(failed))
                put(
                    "failures",
                    buildJsonArray {
                        failures.forEach { failure ->
                            add(
                                buildJsonObject {
                                    put("device_id", JsonPrimitive(failure["device_id"].orEmpty()))
                                    put("reason", JsonPrimitive(failure["reason"].orEmpty()))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    get("/v1/push/health") {
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("status", JsonPrimitive("ok"))
                put("time", JsonPrimitive(Instant.now().toString()))
                put("base_path", JsonPrimitive(pathPrefix.ifBlank { "/" }))
                put("bind_host", JsonPrimitive(bindHost))
                put("bind_port", JsonPrimitive(bindPort))
                put("registered_devices", JsonPrimitive(devices.size))
                put("acks", JsonPrimitive(acks.size))
            },
        )
    }

    get("/v1/push/metrics") {
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("push_sent", JsonPrimitive(pushSent.get()))
                put("push_delivered", JsonPrimitive(pushDelivered.get()))
                put("push_opened", JsonPrimitive(pushOpened.get()))
                put("push_retried", JsonPrimitive(pushRetried.get()))
                put("push_failed", JsonPrimitive(pushFailed.get()))
                put(
                    "failure_reasons",
                    buildJsonObject {
                        failureReasons.forEach { (reason, count) ->
                            put(reason, JsonPrimitive(count.get()))
                        }
                    },
                )
            },
        )
    }

    get("/v1/files/google-services.json") {
        if (!call.requireAuthorized()) return@get
        call.respondJsonFile(googleServicesJsonPath, "google-services.json")
    }

    get("/v1/files/firebase-adminsdk.json") {
        if (!call.requireAuthorized()) return@get
        call.respondJsonFile(adminSdkJsonPath, "firebase-adminsdk.json")
    }
}

private fun resolveTargetDevices(request: PushSendRequest): List<DeviceRegistration> {
    val byDeviceIds = request.deviceIds
        .mapNotNull { deviceId -> devices[deviceId] }

    val byUserId = request.userId
        ?.let { userId -> devices.values.filter { registration -> registration.userId == userId } }
        .orEmpty()

    val byFcmTokens = request.fcmTokens
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .map { token ->
            devices.values.firstOrNull { registration -> registration.fcmToken == token }
                ?: DeviceRegistration(
                    userId = request.userId ?: 0L,
                    deviceId = "direct-token:${token.takeLast(10)}",
                    fcmToken = token,
                    appVersion = "direct_token",
                    locale = "und",
                    capabilities = listOf("direct_token"),
                )
        }

    val all = if (byDeviceIds.isNotEmpty() || byUserId.isNotEmpty() || byFcmTokens.isNotEmpty()) {
        byDeviceIds + byUserId + byFcmTokens
    } else {
        devices.values.toList()
    }
    return all.distinctBy { it.fcmToken }
}

private suspend fun ApplicationCall.requireAuthorized(): Boolean {
    if (sharedSecret.isBlank()) return true
    val provided = request.headers[HEADER_SHARED_SECRET].orEmpty()
    if (provided == sharedSecret) return true
    respond(
        HttpStatusCode.Unauthorized,
        buildJsonObject {
            put("status", JsonPrimitive("unauthorized"))
        },
    )
    return false
}

private suspend fun ApplicationCall.respondJsonFile(path: String, label: String) {
    val file = File(path)
    if (!file.exists() || !file.isFile) {
        respond(
            HttpStatusCode.NotFound,
            buildJsonObject {
                put("status", JsonPrimitive("file_not_found"))
                put("file", JsonPrimitive(label))
            },
        )
        return
    }
    respondFile(file)
}

private object FcmGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val credentialsLock = Any()

    @Volatile
    private var credentials: GoogleCredentials? = null

    @Volatile
    private var projectId: String? = null

    fun sendData(
        token: String,
        data: Map<String, String>,
        priority: String,
        ttlSeconds: Long?,
        collapseKey: String?,
    ): Result<String> {
        return runCatching {
            val resolvedProjectId = getProjectId()
            val accessToken = getAccessToken()
            val endpoint = URL("https://fcm.googleapis.com/v1/projects/$resolvedProjectId/messages:send")
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")

            val priorityValue = if (priority.equals("normal", ignoreCase = true)) "NORMAL" else "HIGH"
            val androidObject = buildJsonObject {
                put("priority", JsonPrimitive(priorityValue))
                ttlSeconds?.takeIf { it > 0 }?.let { put("ttl", JsonPrimitive("${it}s")) }
                collapseKey?.takeIf { it.isNotBlank() }?.let { put("collapse_key", JsonPrimitive(it)) }
            }
            val payloadData = JsonObject(data.mapValues { (_, value) -> JsonPrimitive(value) })
            val messageObject = buildJsonObject {
                put("token", JsonPrimitive(token))
                if (payloadData.isNotEmpty()) {
                    put("data", payloadData)
                }
                put("android", androidObject)
            }
            val requestBody = buildJsonObject {
                put("message", messageObject)
            }.toString()

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val code = connection.responseCode
            val responsePayload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            connection.disconnect()

            if (code !in 200..299) {
                throw IllegalStateException("FCM $code: ${responsePayload.take(600)}")
            }

            runCatching {
                json.parseToJsonElement(responsePayload).jsonObject["name"]?.jsonPrimitive?.content
            }.getOrNull().orEmpty().ifBlank { "ok" }
        }
    }

    private fun getProjectId(): String {
        projectId?.let { return it }
        synchronized(credentialsLock) {
            projectId?.let { return it }

            val configuredProjectId = System.getenv(ENV_FCM_PROJECT_ID)?.trim().orEmpty()
                .ifBlank { DEFAULT_FCM_PROJECT_ID }
            if (configuredProjectId.isNotBlank()) {
                projectId = configuredProjectId
                return configuredProjectId
            }

            val serviceAccountProjectId = (getCredentials() as? ServiceAccountCredentials)?.projectId.orEmpty()
            if (serviceAccountProjectId.isBlank()) {
                throw IllegalStateException("FCM project id is missing. Set $ENV_FCM_PROJECT_ID or provide service account JSON with project_id.")
            }
            projectId = serviceAccountProjectId
            return serviceAccountProjectId
        }
    }

    private fun getAccessToken(): String {
        val creds = getCredentials()
        synchronized(credentialsLock) {
            creds.refreshIfExpired()
            val token = creds.accessToken?.tokenValue.orEmpty()
            if (token.isBlank()) {
                throw IllegalStateException("Cannot acquire FCM access token")
            }
            return token
        }
    }

    private fun getCredentials(): GoogleCredentials {
        credentials?.let { return it }
        synchronized(credentialsLock) {
            credentials?.let { return it }
            val serviceAccountPath = System.getenv(ENV_FCM_SERVICE_ACCOUNT_JSON)?.trim().orEmpty()
            if (serviceAccountPath.isBlank()) {
                throw IllegalStateException("Missing env $ENV_FCM_SERVICE_ACCOUNT_JSON")
            }
            val scoped = FileInputStream(serviceAccountPath).use { input ->
                GoogleCredentials.fromStream(input).createScoped(listOf(FCM_SCOPE))
            }
            credentials = scoped
            return scoped
        }
    }
}

private fun normalizeBasePath(raw: String): String {
    val cleaned = raw.trim()
    if (cleaned.isBlank() || cleaned == "/") return "/"
    val noSlashEnd = cleaned.trimEnd('/')
    return if (noSlashEnd.startsWith("/")) noSlashEnd else "/$noSlashEnd"
}
