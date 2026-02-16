package com.tgweb.app

import android.net.Uri
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import java.net.URI

object ProxyLinkParser {
    private val telegramHosts = setOf("t.me", "telegram.me", "www.t.me", "www.telegram.me")
    private val knownQueryKeys = setOf(
        "server",
        "host",
        "port",
        "p",
        "secret",
        "type",
        "user",
        "username",
        "pass",
        "password",
    )

    fun parse(raw: String?): ProxyConfigSnapshot? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw
            .trim()
            .replace("&amp;", "&")
            .trimEnd('.', ',', ';', ')', ']', '>')
        return runCatching { Uri.parse(normalized) }
            .getOrNull()
            ?.let { parseInternal(it, normalized) }
    }

    fun parse(uri: Uri): ProxyConfigSnapshot? = parseInternal(uri, uri.toString())

    private fun parseInternal(uri: Uri, raw: String): ProxyConfigSnapshot? {
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme.isBlank()) return null

        return when (scheme) {
            "tg" -> parseTelegramScheme(uri)
            "http", "https" -> {
                if (isTelegramProxyLink(uri)) {
                    parseTelegramHttpScheme(uri)
                } else {
                    parseDirectProxyScheme(uri, raw, fallbackType = ProxyType.HTTP)
                }
            }
            "socks", "socks5" -> parseDirectProxyScheme(uri, raw, fallbackType = ProxyType.SOCKS5)
            "mtproto" -> parseDirectProxyScheme(uri, raw, fallbackType = ProxyType.MTPROTO)
            else -> null
        }
    }

    private fun parseTelegramScheme(uri: Uri): ProxyConfigSnapshot? {
        val host = uri.host.orEmpty().lowercase()
        if (host !in setOf("proxy", "socks", "http", "https")) return null

        val server = firstNotBlank(query(uri, "server"), query(uri, "host")) ?: return null
        val rawPort = firstNotBlank(query(uri, "port"), query(uri, "p"))
        val secret = query(uri, "secret").orEmpty().trim()
        val username = firstNotBlank(query(uri, "user"), query(uri, "username"))
        val password = firstNotBlank(query(uri, "pass"), query(uri, "password"))
        val requestedType = query(uri, "type").orEmpty().trim().uppercase()

        val inferredType = when {
            secret.isNotBlank() -> ProxyType.MTPROTO
            host == "socks" -> ProxyType.SOCKS5
            host == "http" || host == "https" -> ProxyType.HTTP
            requestedType == ProxyType.HTTP.name -> ProxyType.HTTP
            requestedType == ProxyType.SOCKS5.name || requestedType == "SOCKS" -> ProxyType.SOCKS5
            requestedType == ProxyType.MTPROTO.name -> ProxyType.MTPROTO
            username != null || password != null -> ProxyType.SOCKS5
            else -> ProxyType.HTTP
        }

        return buildSnapshot(
            type = inferredType,
            host = server,
            rawPort = rawPort,
            username = username,
            password = password,
            secret = secret,
        )
    }

    private fun parseTelegramHttpScheme(uri: Uri): ProxyConfigSnapshot? {
        val path = uri.path.orEmpty().lowercase()
        val server = firstNotBlank(query(uri, "server"), query(uri, "host")) ?: return null
        val rawPort = firstNotBlank(query(uri, "port"), query(uri, "p"))
        val secret = query(uri, "secret").orEmpty().trim()
        val username = firstNotBlank(query(uri, "user"), query(uri, "username"))
        val password = firstNotBlank(query(uri, "pass"), query(uri, "password"))
        val requestedType = query(uri, "type").orEmpty().trim().uppercase()

        val inferredType = when {
            secret.isNotBlank() -> ProxyType.MTPROTO
            path.startsWith("/socks") -> ProxyType.SOCKS5
            requestedType == ProxyType.HTTP.name -> ProxyType.HTTP
            requestedType == ProxyType.SOCKS5.name || requestedType == "SOCKS" -> ProxyType.SOCKS5
            requestedType == ProxyType.MTPROTO.name -> ProxyType.MTPROTO
            username != null || password != null -> ProxyType.SOCKS5
            else -> ProxyType.HTTP
        }

        return buildSnapshot(
            type = inferredType,
            host = server,
            rawPort = rawPort,
            username = username,
            password = password,
            secret = secret,
        )
    }

    private fun parseDirectProxyScheme(
        uri: Uri,
        raw: String,
        fallbackType: ProxyType,
    ): ProxyConfigSnapshot? {
        if (!isLikelyDirectProxyUri(uri)) return null

        val (authorityUser, authorityPass) = extractUserAndPassword(raw)
        val host = firstNotBlank(query(uri, "server"), query(uri, "host"), uri.host) ?: return null
        val rawPort = firstNotBlank(query(uri, "port"), query(uri, "p"))
        val requestedType = query(uri, "type").orEmpty().trim().uppercase()
        val secret = query(uri, "secret").orEmpty().trim()
        val username = firstNotBlank(query(uri, "user"), query(uri, "username"), authorityUser)
        val password = firstNotBlank(query(uri, "pass"), query(uri, "password"), authorityPass)

        val inferredType = when {
            secret.isNotBlank() -> ProxyType.MTPROTO
            requestedType == ProxyType.HTTP.name -> ProxyType.HTTP
            requestedType == ProxyType.SOCKS5.name || requestedType == "SOCKS" -> ProxyType.SOCKS5
            requestedType == ProxyType.MTPROTO.name -> ProxyType.MTPROTO
            fallbackType == ProxyType.MTPROTO -> ProxyType.MTPROTO
            fallbackType == ProxyType.SOCKS5 -> ProxyType.SOCKS5
            else -> ProxyType.HTTP
        }

        val directPort = uri.port.takeIf { it in 1..65535 }?.toString()
        return buildSnapshot(
            type = inferredType,
            host = host,
            rawPort = rawPort ?: directPort,
            username = username,
            password = password,
            secret = secret,
        )
    }

    private fun buildSnapshot(
        type: ProxyType,
        host: String,
        rawPort: String?,
        username: String?,
        password: String?,
        secret: String?,
    ): ProxyConfigSnapshot? {
        val resolvedPort = rawPort?.toIntOrNull() ?: when (type) {
            ProxyType.MTPROTO -> 443
            ProxyType.SOCKS5 -> 1080
            ProxyType.HTTP -> 8080
            ProxyType.DIRECT -> 0
        }
        if (resolvedPort !in 1..65535) return null
        if (type == ProxyType.MTPROTO && secret.isNullOrBlank()) return null

        return ProxyConfigSnapshot(
            enabled = true,
            type = type,
            host = host.trim(),
            port = resolvedPort,
            username = username?.trim()?.ifBlank { null },
            password = password?.trim()?.ifBlank { null },
            secret = if (type == ProxyType.MTPROTO) secret?.trim()?.ifBlank { null } else null,
        )
    }

    private fun isLikelyDirectProxyUri(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme == "socks" || scheme == "socks5" || scheme == "mtproto") return true

        if (query(uri, "secret").isNotBlank()) return true
        if (query(uri, "server").isNotBlank() && query(uri, "port").isNotBlank()) return true

        val rawType = query(uri, "type").orEmpty().trim().uppercase()
        if (rawType in setOf(ProxyType.HTTP.name, ProxyType.SOCKS5.name, "SOCKS", ProxyType.MTPROTO.name)) return true

        val hasExplicitPort = uri.port in 1..65535
        val simplePath = uri.path.isNullOrBlank() || uri.path == "/"
        if (!hasExplicitPort || !simplePath) return false

        if (uri.query.isNullOrBlank()) return true
        return uri.queryParameterNames.all { knownQueryKeys.contains(it.lowercase()) }
    }

    private fun isTelegramProxyLink(uri: Uri): Boolean {
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        return host in telegramHosts &&
            (path.startsWith("/proxy") || path.startsWith("/socks"))
    }

    private fun extractUserAndPassword(raw: String): Pair<String?, String?> {
        val userInfo = runCatching { URI(raw).rawUserInfo }.getOrNull().orEmpty()
        if (userInfo.isBlank()) return null to null
        val parts = userInfo.split(':', limit = 2)
        val username = Uri.decode(parts[0]).trim().ifBlank { null }
        val password = parts.getOrNull(1)?.let(Uri::decode)?.trim()?.ifBlank { null }
        return username to password
    }

    private fun query(uri: Uri, key: String): String? = uri.getQueryParameter(key)

    private fun String?.isNotBlank(): Boolean = !this.isNullOrBlank()

    private fun firstNotBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }
}
