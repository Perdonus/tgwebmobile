package com.tgweb.core.data

import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TelegramProxyProbeFailure {
    DISABLED,
    TIMEOUT,
    AUTH_REQUIRED,
    UNREACHABLE,
    MTPROTO_UNAVAILABLE,
}

data class TelegramProxyProbeResult(
    val latencyMs: Double? = null,
    val failure: TelegramProxyProbeFailure? = null,
    val responseCode: Int? = null,
    val details: String = "",
)

object ProxyTransportUtils {
    const val TELEGRAM_PROBE_URL = "https://web.telegram.org/k/"

    fun buildNetworkProxy(
        proxyState: ProxyConfigSnapshot,
        allowMtprotoAsSocks: Boolean = false,
    ): Proxy {
        if (!proxyState.enabled || proxyState.type == ProxyType.DIRECT) {
            return Proxy.NO_PROXY
        }

        return when (proxyState.type) {
            ProxyType.HTTP -> Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyState.host, proxyState.port))
            ProxyType.SOCKS5 -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyState.host, proxyState.port))
            ProxyType.MTPROTO -> {
                if (allowMtprotoAsSocks) {
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyState.host, proxyState.port))
                } else {
                    Proxy.NO_PROXY
                }
            }
            ProxyType.DIRECT -> Proxy.NO_PROXY
        }
    }

    fun applyProxyAuth(
        connection: HttpURLConnection,
        proxyState: ProxyConfigSnapshot,
    ) {
        if (proxyState.type != ProxyType.HTTP) return
        val username = proxyState.username?.trim().orEmpty()
        val password = proxyState.password?.trim().orEmpty()
        if (username.isBlank() || password.isBlank()) return

        val token = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        connection.setRequestProperty("Proxy-Authorization", "Basic $token")
    }

    suspend fun measureTelegramReachability(
        proxyState: ProxyConfigSnapshot,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
    ): TelegramProxyProbeResult = withContext(Dispatchers.IO) {
        if (!proxyState.enabled || proxyState.type == ProxyType.DIRECT) {
            return@withContext TelegramProxyProbeResult(
                failure = TelegramProxyProbeFailure.DISABLED,
                details = "Proxy is disabled",
            )
        }

        if (proxyState.type == ProxyType.MTPROTO) {
            val latency = runCatching {
                if (AppRepositories.isInitialized()) {
                    AppRepositories.tdLibGateway.measureTelegramLatency(proxyState)
                } else {
                    null
                }
            }.getOrNull()

            return@withContext if (latency != null) {
                TelegramProxyProbeResult(
                    latencyMs = latency,
                    responseCode = 200,
                    details = "Telegram endpoint reached through MTProto transport",
                )
            } else {
                TelegramProxyProbeResult(
                    failure = TelegramProxyProbeFailure.MTPROTO_UNAVAILABLE,
                    details = "TDLib backend is not available for MTProto probe",
                )
            }
        }

        val startedAt = System.nanoTime()
        runCatching {
            val connection = openTelegramProbeConnection(proxyState, connectTimeoutMs, readTimeoutMs)
            try {
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_PROXY_AUTH -> {
                        TelegramProxyProbeResult(
                            failure = TelegramProxyProbeFailure.AUTH_REQUIRED,
                            responseCode = code,
                            details = "Proxy requested authentication",
                        )
                    }
                    code in 200..499 -> {
                        TelegramProxyProbeResult(
                            latencyMs = (System.nanoTime() - startedAt) / 1_000_000.0,
                            responseCode = code,
                            details = "Telegram endpoint reached via ${proxyState.type.name.lowercase()} proxy",
                        )
                    }
                    else -> {
                        TelegramProxyProbeResult(
                            failure = TelegramProxyProbeFailure.UNREACHABLE,
                            responseCode = code,
                            details = "Unexpected HTTP $code from Telegram probe",
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            val failure = when (error) {
                is SocketTimeoutException -> TelegramProxyProbeFailure.TIMEOUT
                else -> TelegramProxyProbeFailure.UNREACHABLE
            }
            TelegramProxyProbeResult(
                failure = failure,
                details = "${error::class.java.simpleName}: ${error.message}",
            )
        }
    }

    private fun openTelegramProbeConnection(
        proxyState: ProxyConfigSnapshot,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val connection = URL(TELEGRAM_PROBE_URL)
            .openConnection(buildNetworkProxy(proxyState)) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-store")
        applyProxyAuth(connection, proxyState)
        connection.connect()
        return connection
    }
}
