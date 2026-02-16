package com.tgweb.app

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlin.math.roundToInt

class ProxySettingsActivity : AppCompatActivity() {
    private lateinit var proxyEnabledSwitch: SwitchCompat
    private lateinit var proxyList: ListView
    private lateinit var addButton: Button
    private lateinit var importButton: Button
    private lateinit var statusText: TextView
    private lateinit var adapter: ArrayAdapter<String>

    private var profiles: MutableList<ProxyProfile> = mutableListOf()
    private var selectedProfileId: String? = null
    private var healthLoopJob: Job? = null
    private val health = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val surfaceColor = UiThemeBridge.resolveSettingsSurfaceColor(this)
        setTheme(
            if (UiThemeBridge.isLight(surfaceColor)) {
                R.style.Theme_TGWeb_Settings_Light
            } else {
                R.style.Theme_TGWeb_Settings_Dark
            },
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_settings)
        UiThemeBridge.applyWindowColors(this, surfaceColor)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), surfaceColor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.proxy_settings_title)

        proxyEnabledSwitch = findViewById(R.id.proxyEnabledSwitch)
        proxyList = findViewById(R.id.proxyProfilesList)
        addButton = findViewById(R.id.proxyAddButton)
        importButton = findViewById(R.id.proxyImportFromLinkButton)
        statusText = findViewById(R.id.proxyListStatusText)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        proxyList.adapter = adapter

        bindActions()
        reloadProfiles()

        intent?.data?.let { uri ->
            handleIncomingProxyUri(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        startHealthLoop()
    }

    override fun onStop() {
        healthLoopJob?.cancel()
        healthLoopJob = null
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun bindActions() {
        proxyEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
            ProxyProfilesStore.setProxyEnabled(this, enabled)
            applyCurrentProxyState()
            renderList()
        }

        addButton.setOnClickListener { showProxyEditorDialog() }
        importButton.setOnClickListener { showImportLinkDialog() }

        proxyList.setOnItemClickListener { _, _, position, _ ->
            val profile = profiles.getOrNull(position) ?: return@setOnItemClickListener
            selectedProfileId = profile.id
            ProxyProfilesStore.setActiveProfileId(this, profile.id)
            applyCurrentProxyState()
            renderList()
        }

        proxyList.setOnItemLongClickListener { _, _, position, _ ->
            val profile = profiles.getOrNull(position) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle(profile.title)
                .setItems(arrayOf(getString(R.string.proxy_edit), getString(R.string.proxy_delete))) { _, which ->
                    when (which) {
                        0 -> showProxyEditorDialog(profile)
                        1 -> {
                            profiles = ProxyProfilesStore.remove(this, profile.id).toMutableList()
                            if (selectedProfileId == profile.id) {
                                selectedProfileId = ProxyProfilesStore.getActiveProfileId(this)
                            }
                            applyCurrentProxyState()
                            renderList()
                        }
                    }
                }
                .show()
            true
        }
    }

    private fun reloadProfiles() {
        profiles = ProxyProfilesStore.load(this).toMutableList()
        selectedProfileId = ProxyProfilesStore.getActiveProfileId(this)
        proxyEnabledSwitch.isChecked = ProxyProfilesStore.isProxyEnabled(this)
        renderList()
        applyCurrentProxyState()
    }

    private fun renderList() {
        if (profiles.isEmpty()) {
            statusText.text = getString(R.string.proxy_profiles_empty)
        } else {
            statusText.text = getString(R.string.proxy_profiles_hint)
        }

        val selectedId = selectedProfileId
        val enabled = proxyEnabledSwitch.isChecked
        val rows = profiles.map { profile ->
            val prefix = when {
                profile.id == selectedId && enabled -> "●"
                profile.id == selectedId -> "○"
                else -> "·"
            }
            val healthText = health[profile.id] ?: getString(R.string.proxy_health_checking)
            "$prefix ${profile.title}\n${profile.config.host}:${profile.config.port} • ${profile.config.type.name} • $healthText"
        }
        adapter.clear()
        adapter.addAll(rows)
        adapter.notifyDataSetChanged()
    }

    private fun applyCurrentProxyState() {
        lifecycleScope.launch {
            val state = ProxyProfilesStore.resolveActiveConfig(this@ProxySettingsActivity)
            AppRepositories.updateProxyState(state)
        }
    }

    private fun handleIncomingProxyUri(uri: Uri) {
        val parsed = ProxyLinkParser.parse(uri) ?: return
        val profile = ProxyProfile(
            title = ProxyProfilesStore.defaultTitle(parsed),
            config = parsed.copy(enabled = true),
        )
        ProxyProfilesStore.upsert(this, profile)
        ProxyProfilesStore.setActiveProfileId(this, profile.id)
        ProxyProfilesStore.setProxyEnabled(this, true)
        reloadProfiles()
        showToast(getString(R.string.proxy_import_applied))
    }

    private fun showImportLinkDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.proxy_link_import_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.proxy_link_import_title)
            .setView(input)
            .setPositiveButton(R.string.proxy_import_button) { _, _ ->
                val parsed = ProxyLinkParser.parse(input.text?.toString())
                if (parsed == null) {
                    showToast(getString(R.string.proxy_import_invalid))
                    return@setPositiveButton
                }
                val profile = ProxyProfile(
                    title = ProxyProfilesStore.defaultTitle(parsed),
                    config = parsed.copy(enabled = true),
                )
                ProxyProfilesStore.upsert(this, profile)
                ProxyProfilesStore.setActiveProfileId(this, profile.id)
                ProxyProfilesStore.setProxyEnabled(this, true)
                reloadProfiles()
                showToast(getString(R.string.proxy_import_applied))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProxyEditorDialog(existing: ProxyProfile? = null) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 8)
        }

        fun makeInput(hint: String, value: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                this.hint = hint
                this.setText(value)
                this.inputType = inputType
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }

        val titleInput = makeInput(getString(R.string.proxy_profile_name), existing?.title.orEmpty())
        val hostInput = makeInput(getString(R.string.proxy_host), existing?.config?.host.orEmpty())
        val portInput = makeInput(
            getString(R.string.proxy_port),
            existing?.config?.port?.takeIf { it > 0 }?.toString().orEmpty(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val usernameInput = makeInput(getString(R.string.proxy_username), existing?.config?.username.orEmpty())
        val passwordInput = makeInput(getString(R.string.proxy_password), existing?.config?.password.orEmpty())
        val secretInput = makeInput(getString(R.string.proxy_secret), existing?.config?.secret.orEmpty())
        val typeSpinner = Spinner(this).apply {
            val values = listOf(ProxyType.HTTP.name, ProxyType.SOCKS5.name, ProxyType.MTPROTO.name)
            adapter = ArrayAdapter(
                this@ProxySettingsActivity,
                android.R.layout.simple_spinner_item,
                values,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val selected = existing?.config?.type?.name ?: ProxyType.HTTP.name
            setSelection(values.indexOf(selected).coerceAtLeast(0))
        }

        container.addView(titleInput)
        container.addView(typeSpinner)
        container.addView(hostInput)
        container.addView(portInput)
        container.addView(usernameInput)
        container.addView(passwordInput)
        container.addView(secretInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.proxy_add else R.string.proxy_edit)
            .setView(container)
            .setPositiveButton(R.string.proxy_apply_button) { _, _ ->
                val host = hostInput.text?.toString()?.trim().orEmpty()
                val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val type = runCatching {
                    ProxyType.valueOf(typeSpinner.selectedItem?.toString().orEmpty())
                }.getOrDefault(ProxyType.HTTP)
                val username = usernameInput.text?.toString()?.trim().orEmpty().ifBlank { null }
                val password = passwordInput.text?.toString()?.trim().orEmpty().ifBlank { null }
                val secret = secretInput.text?.toString()?.trim().orEmpty().ifBlank { null }

                if (host.isBlank() || port !in 1..65535) {
                    showToast(getString(R.string.proxy_invalid_host_port))
                    return@setPositiveButton
                }
                if (type == ProxyType.MTPROTO && secret.isNullOrBlank()) {
                    showToast(getString(R.string.proxy_secret_required))
                    return@setPositiveButton
                }

                val config = ProxyConfigSnapshot(
                    enabled = true,
                    type = type,
                    host = host,
                    port = port,
                    username = if (type == ProxyType.MTPROTO) null else username,
                    password = if (type == ProxyType.MTPROTO) null else password,
                    secret = if (type == ProxyType.MTPROTO) secret else null,
                )
                val profile = ProxyProfile(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    title = titleInput.text?.toString()?.trim().orEmpty().ifBlank {
                        ProxyProfilesStore.defaultTitle(config)
                    },
                    config = config,
                )
                ProxyProfilesStore.upsert(this, profile)
                ProxyProfilesStore.setActiveProfileId(this, profile.id)
                ProxyProfilesStore.setProxyEnabled(this, proxyEnabledSwitch.isChecked)
                reloadProfiles()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startHealthLoop() {
        healthLoopJob?.cancel()
        healthLoopJob = lifecycleScope.launch {
            while (isActive) {
                val current = profiles.toList()
                if (current.isEmpty()) {
                    delay(PROXY_HEALTH_INTERVAL_MS)
                    continue
                }

                current.forEach { profile ->
                    val latency = withTimeoutOrNull(PROXY_HEALTH_TIMEOUT_MS) {
                        measureProxyLatency(profile.config)
                    }
                    health[profile.id] = if (latency != null) {
                        getString(R.string.proxy_health_ok, latency.roundToInt())
                    } else {
                        getString(R.string.proxy_health_timeout)
                    }
                    renderList()
                }
                delay(PROXY_HEALTH_INTERVAL_MS)
            }
        }
    }

    private suspend fun measureProxyLatency(state: ProxyConfigSnapshot): Double? = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        runCatching {
            when (state.type) {
                ProxyType.MTPROTO -> {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(state.host, state.port), CONNECT_TIMEOUT_MS)
                    }
                }
                ProxyType.SOCKS5 -> {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(state.host, state.port))
                    URL(PING_URL).openConnection(proxy).let { connection ->
                        val http = connection as HttpURLConnection
                        http.instanceFollowRedirects = false
                        http.connectTimeout = CONNECT_TIMEOUT_MS
                        http.readTimeout = READ_TIMEOUT_MS
                        http.requestMethod = "HEAD"
                        http.connect()
                        http.responseCode
                        http.disconnect()
                    }
                }
                ProxyType.HTTP -> {
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(state.host, state.port))
                    URL(PING_URL).openConnection(proxy).let { connection ->
                        val http = connection as HttpURLConnection
                        http.instanceFollowRedirects = false
                        http.connectTimeout = CONNECT_TIMEOUT_MS
                        http.readTimeout = READ_TIMEOUT_MS
                        http.requestMethod = "HEAD"
                        http.connect()
                        http.responseCode
                        http.disconnect()
                    }
                }
                ProxyType.DIRECT -> return@withContext null
            }
            (System.nanoTime() - startedAt) / 1_000_000.0
        }.getOrNull()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PROXY_HEALTH_INTERVAL_MS = 10_000L
        private const val PROXY_HEALTH_TIMEOUT_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val PING_URL = "https://web.telegram.org/"
    }
}

