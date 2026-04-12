package com.tgweb.app

import android.content.Context
import com.tgweb.core.data.TelegramProxyProbeFailure
import com.tgweb.core.data.TelegramProxyProbeResult
import kotlin.math.roundToInt

fun Context.formatProxyHealth(result: TelegramProxyProbeResult): String {
    result.latencyMs?.let { latency ->
        return getString(R.string.proxy_health_ok, latency.roundToInt())
    }

    return when (result.failure) {
        TelegramProxyProbeFailure.DISABLED -> getString(R.string.proxy_health_disabled)
        TelegramProxyProbeFailure.TIMEOUT -> getString(R.string.proxy_health_timeout)
        TelegramProxyProbeFailure.AUTH_REQUIRED -> getString(R.string.proxy_health_auth_required)
        TelegramProxyProbeFailure.MTPROTO_UNAVAILABLE -> getString(R.string.proxy_health_mtproto_unavailable)
        TelegramProxyProbeFailure.UNREACHABLE,
        null,
        -> getString(R.string.proxy_health_unreachable)
    }
}
