package com.tgweb.app

import android.content.Context
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val config: ProxyConfigSnapshot,
)

object ProxyProfilesStore {
    private const val PREFS = KeepAliveService.PREFS
    private const val KEY_PROFILES = "proxy_profiles_v1"
    private const val KEY_ACTIVE_PROFILE = "proxy_active_profile"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"

    fun load(context: Context): List<ProxyProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, "[]").orEmpty()
        val root = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val items = mutableListOf<ProxyProfile>()
        for (index in 0 until root.length()) {
            val entry = root.optJSONObject(index) ?: continue
            val type = runCatching {
                ProxyType.valueOf(entry.optString("type", ProxyType.HTTP.name))
            }.getOrDefault(ProxyType.HTTP)
            val host = entry.optString("host", "").trim()
            val port = entry.optInt("port", 0)
            if (host.isBlank() || port !in 1..65535) continue
            val config = ProxyConfigSnapshot(
                enabled = true,
                type = type,
                host = host,
                port = port,
                username = entry.optString("username", "").ifBlank { null },
                password = entry.optString("password", "").ifBlank { null },
                secret = entry.optString("secret", "").ifBlank { null },
            )
            items += ProxyProfile(
                id = entry.optString("id", UUID.randomUUID().toString()),
                title = entry.optString("title", defaultTitle(config)).ifBlank { defaultTitle(config) },
                config = config,
            )
        }
        return items
    }

    fun save(context: Context, profiles: List<ProxyProfile>) {
        val payload = JSONArray()
        profiles.forEach { profile ->
            payload.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("title", profile.title)
                    .put("type", profile.config.type.name)
                    .put("host", profile.config.host)
                    .put("port", profile.config.port)
                    .put("username", profile.config.username ?: "")
                    .put("password", profile.config.password ?: "")
                    .put("secret", profile.config.secret ?: ""),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, payload.toString())
            .apply()
    }

    fun upsert(context: Context, profile: ProxyProfile): List<ProxyProfile> {
        val mutable = load(context).toMutableList()
        val existingIndex = mutable.indexOfFirst { it.id == profile.id }
        if (existingIndex >= 0) {
            mutable[existingIndex] = profile
        } else {
            mutable += profile
        }
        save(context, mutable)
        return mutable
    }

    fun remove(context: Context, profileId: String): List<ProxyProfile> {
        val mutable = load(context).toMutableList()
        mutable.removeAll { it.id == profileId }
        save(context, mutable)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE_PROFILE, null) == profileId) {
            prefs.edit().remove(KEY_ACTIVE_PROFILE).apply()
        }
        return mutable
    }

    fun setActiveProfileId(context: Context, profileId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PROFILE, profileId)
            .apply()
    }

    fun getActiveProfileId(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE, null)
    }

    fun setProxyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROXY_ENABLED, enabled)
            .apply()
    }

    fun isProxyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROXY_ENABLED, false)
    }

    fun resolveActiveConfig(context: Context): ProxyConfigSnapshot {
        if (!isProxyEnabled(context)) {
            return ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)
        }
        val activeId = getActiveProfileId(context)
        val profile = load(context).firstOrNull { it.id == activeId } ?: return ProxyConfigSnapshot(
            enabled = false,
            type = ProxyType.DIRECT,
        )
        return profile.config.copy(enabled = true)
    }

    fun defaultTitle(config: ProxyConfigSnapshot): String {
        return "${config.type.name} ${config.host}:${config.port}"
    }
}
