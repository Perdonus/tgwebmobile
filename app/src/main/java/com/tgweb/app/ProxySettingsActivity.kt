package com.tgweb.app

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
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
    private lateinit var proxyEnabledSwitch: MaterialSwitch
    private lateinit var proxyList: ListView
    private lateinit var addButton: Button
    private lateinit var importButton: Button
    private lateinit var statusText: TextView
    private lateinit var adapter: ArrayAdapter<ProxyProfile>
    private lateinit var palette: UiThemeBridge.SettingsPalette

    private var profiles: MutableList<ProxyProfile> = mutableListOf()
    private var selectedProfileId: String? = null
    private var healthLoopJob: Job? = null
    private val health = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiThemeBridge.prepareSettingsTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_settings)

        palette = UiThemeBridge.resolveSettingsPalette(this)
        UiThemeBridge.applyWindowColors(this, palette)
        UiThemeBridge.applyContentContrast(findViewById(android.R.id.content), palette)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.proxy_settings_title)

        proxyEnabledSwitch = findViewById(R.id.proxyEnabledSwitch)
        proxyList = findViewById(R.id.proxyProfilesList)
        addButton = findViewById(R.id.proxyAddButton)
        importButton = findViewById(R.id.proxyImportFromLinkButton)
        statusText = findViewById(R.id.proxyListStatusText)
        adapter = object : ArrayAdapter<ProxyProfile>(this, android.R.layout.simple_list_item_2, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val profile = getItem(position) ?: return view
                val titleView = view.findViewById<TextView>(android.R.id.text1)
                val subtitleView = view.findViewById<TextView>(android.R.id.text2)
                val active = proxyEnabledSwitch.isChecked && profile.id == selectedProfileId
                val statusPrefix = when {
                    active -> "Активен"
                    profile.id == selectedProfileId -> "Выбран"
                    else -> "Сохранён"
                }
                val healthText = health[profile.id] ?: getString(R.string.proxy_health_checking)
                titleView.text = profile.title
                subtitleView.text = "$statusPrefix • ${profile.config.host}:${profile.config.port} • ${profile.config.type.name} • $healthText"
                UiThemeBridge.styleTwoLineListRow(
                    context = this@ProxySettingsActivity,
                    rowView = view,
                    titleView = titleView,
                    subtitleView = subtitleView,
                    palette = palette,
                    activated = active,
                )
                return view
            }
        }
        proxyList.adapter = adapter
        UiThemeBridge.styleListView(proxyList, palette)

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
            MaterialAlertDialogBuilder(this)
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
                .setNegativeButton(android.R.string.cancel, null)
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
        statusText.text = if (profiles.isEmpty()) {
            getString(R.string.proxy_profiles_empty)
        } else {
            getString(R.string.proxy_profiles_hint)
        }
        adapter.clear()
        adapter.addAll(profiles)
        adapter.notifyDataSetChanged()
    }

    private fun applyCurrentProxyState() {
        lifecycleScope.launch {
            val state = ProxyProfilesStore.resolveActiveConfig(this@ProxySettingsActivity)
            AppRepositories.updateProxyState(state)
            DebugLogStore.log(
                "PROXY",
                "Apply state from settings: enabled=${state.enabled} type=${state.type.name} host=${state.host}:${state.port} auth=${!state.username.isNullOrBlank() || !state.password.isNullOrBlank()}",
            )
        }
    }

    private fun handleIncomingProxyUri(uri: Uri) {
        val parsed = ProxyLinkParser.parse(uri) ?: return
        DebugLogStore.log("PROXY", "Incoming proxy URI imported: $uri")
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
        val field = createTextField(
            hint = getString(R.string.proxy_link_import_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        field.editText?.minLines = 2
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxy_link_import_title)
            .setView(wrapDialogContent(field))
            .setPositiveButton(R.string.proxy_import_button) { _, _ ->
                val parsed = ProxyLinkParser.parse(field.editText?.text?.toString())
                if (parsed == null) {
                    showToast(getString(R.string.proxy_import_invalid))
                    DebugLogStore.log("PROXY", "Proxy import failed: invalid link")
                    return@setPositiveButton
                }
                DebugLogStore.log("PROXY", "Proxy import success: ${parsed.type.name}://${parsed.host}:${parsed.port}")
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
        val titleField = createTextField(
            hint = getString(R.string.proxy_profile_name),
            value = existing?.title.orEmpty(),
        )
        val hostField = createTextField(
            hint = getString(R.string.proxy_host),
            value = existing?.config?.host.orEmpty(),
        )
        val portField = createTextField(
            hint = getString(R.string.proxy_port),
            value = existing?.config?.port?.takeIf { it > 0 }?.toString().orEmpty(),
            inputType = InputType.TYPE_CLASS_NUMBER,
        )
        val usernameField = createTextField(
            hint = getString(R.string.proxy_username),
            value = existing?.config?.username.orEmpty(),
        )
        val passwordField = createTextField(
            hint = getString(R.string.proxy_password),
            value = existing?.config?.password.orEmpty(),
        )
        val secretField = createTextField(
            hint = getString(R.string.proxy_secret),
            value = existing?.config?.secret.orEmpty(),
        )
        val typeField = TextInputLayout(this).apply {
            hint = getString(R.string.proxy_type)
            isHintEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = UiThemeBridge.dp(context, 12)
            }
            val input = MaterialAutoCompleteTextView(context).apply {
                inputType = InputType.TYPE_NULL
                isFocusable = false
                val values = listOf(ProxyType.HTTP.name, ProxyType.SOCKS5.name, ProxyType.MTPROTO.name)
                setSimpleItems(values.toTypedArray())
                setText(existing?.config?.type?.name ?: ProxyType.HTTP.name, false)
            }
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            tag = input
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleField)
            addView(typeField)
            addView(hostField)
            addView(portField)
            addView(usernameField)
            addView(passwordField)
            addView(secretField)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.proxy_add else R.string.proxy_edit)
            .setView(wrapDialogContent(content))
            .setPositiveButton(R.string.proxy_apply_button) { _, _ ->
                val host = hostField.editText?.text?.toString()?.trim().orEmpty()
                val port = portField.editText?.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val typeValue = (typeField.tag as? MaterialAutoCompleteTextView)?.text?.toString().orEmpty()
                val type = runCatching { ProxyType.valueOf(typeValue) }.getOrDefault(ProxyType.HTTP)
                val username = usernameField.editText?.text?.toString()?.trim().orEmpty().ifBlank { null }
                val password = passwordField.editText?.text?.toString()?.trim().orEmpty().ifBlank { null }
                val secret = secretField.editText?.text?.toString()?.trim().orEmpty().ifBlank { null }

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
                    title = titleField.editText?.text?.toString()?.trim().orEmpty().ifBlank {
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
                    DebugLogStore.log(
                        "PROXY",
                        "Health check ${profile.title} ${profile.config.type.name}://${profile.config.host}:${profile.config.port} -> ${health[profile.id]}",
                    )
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
        }.onFailure {
            DebugLogStore.log(
                "PROXY",
                "Latency check failed for ${state.type.name}://${state.host}:${state.port}: ${it::class.java.simpleName}: ${it.message}",
            )
        }.getOrNull()
    }

    private fun createTextField(
        hint: String,
        value: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): TextInputLayout {
        return TextInputLayout(this).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = UiThemeBridge.dp(context, 12)
            }
            val input = TextInputEditText(context).apply {
                setText(value)
                this.inputType = inputType
            }
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun wrapDialogContent(content: View): View {
        return ScrollView(this).apply {
            setPadding(
                UiThemeBridge.dp(context, 4),
                UiThemeBridge.dp(context, 4),
                UiThemeBridge.dp(context, 4),
                0,
            )
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
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
