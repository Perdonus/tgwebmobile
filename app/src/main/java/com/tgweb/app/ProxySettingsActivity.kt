package com.tgweb.app

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
    private val proxyTypeValues = listOf(ProxyType.HTTP.name, ProxyType.SOCKS5.name, ProxyType.MTPROTO.name)

    private lateinit var proxyEnabledSwitch: SwitchCompat
    private lateinit var proxyTypeSpinner: Spinner
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var linkInput: EditText
    private lateinit var proxyHealthText: TextView

    private lateinit var serverBlock: View
    private lateinit var authBlock: View
    private lateinit var secretBlock: View
    private var proxyHealthJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val surfaceColor = UiThemeBridge.readSurfaceColor(this)
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

        bindViews()
        configureTypeSpinner()
        bindListeners()

        renderState(AppRepositories.getProxyState())

        intent?.data?.let { uri ->
            applyProxyFromUri(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        startProxyHealthLoop()
    }

    override fun onStop() {
        proxyHealthJob?.cancel()
        proxyHealthJob = null
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        proxyEnabledSwitch = findViewById(R.id.proxyEnabledSwitch)
        proxyTypeSpinner = findViewById(R.id.proxyTypeSpinner)
        hostInput = findViewById(R.id.proxyHostInput)
        portInput = findViewById(R.id.proxyPortInput)
        usernameInput = findViewById(R.id.proxyUsernameInput)
        passwordInput = findViewById(R.id.proxyPasswordInput)
        secretInput = findViewById(R.id.proxySecretInput)
        linkInput = findViewById(R.id.proxyLinkInput)
        proxyHealthText = findViewById(R.id.proxyHealthText)

        serverBlock = findViewById(R.id.proxyServerBlock)
        authBlock = findViewById(R.id.proxyAuthBlock)
        secretBlock = findViewById(R.id.proxySecretBlock)
    }

    private fun configureTypeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, proxyTypeValues)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        proxyTypeSpinner.adapter = adapter
    }

    private fun bindListeners() {
        proxyEnabledSwitch.setOnCheckedChangeListener { _, _ -> updateFieldVisibility() }
        proxyTypeSpinner.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateFieldVisibility()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            },
        )

        findViewById<Button>(R.id.proxyImportButton).setOnClickListener {
            val raw = linkInput.text?.toString().orEmpty()
            if (raw.isBlank()) {
                showToast(getString(R.string.proxy_import_empty))
                return@setOnClickListener
            }
            val parsed = ProxyLinkParser.parse(raw)
            if (parsed == null) {
                showToast(getString(R.string.proxy_import_invalid))
                return@setOnClickListener
            }
            renderState(parsed)
            showToast(getString(R.string.proxy_import_applied))
        }

        findViewById<Button>(R.id.proxySaveButton).setOnClickListener {
            val state = readStateFromForm(showErrors = true) ?: return@setOnClickListener
            persistProxyState(state)
        }

        findViewById<Button>(R.id.proxyDisableButton).setOnClickListener {
            val disabled = ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)
            renderState(disabled)
            persistProxyState(disabled)
        }
    }

    private fun applyProxyFromUri(uri: Uri) {
        val parsed = ProxyLinkParser.parse(uri) ?: return
        renderState(parsed)
        persistProxyState(parsed)
        showToast(getString(R.string.proxy_import_applied))
    }

    private fun persistProxyState(state: ProxyConfigSnapshot) {
        lifecycleScope.launch {
            AppRepositories.updateProxyState(state)
            showToast(getString(R.string.proxy_saved))
            startProxyHealthLoop()
        }
    }

    private fun renderState(state: ProxyConfigSnapshot) {
        proxyEnabledSwitch.isChecked = state.enabled

        val selectedType = when (state.type) {
            ProxyType.HTTP -> ProxyType.HTTP.name
            ProxyType.SOCKS5 -> ProxyType.SOCKS5.name
            ProxyType.MTPROTO -> ProxyType.MTPROTO.name
            ProxyType.DIRECT -> ProxyType.HTTP.name
        }

        val index = proxyTypeValues.indexOf(selectedType)
        if (index >= 0) {
            proxyTypeSpinner.setSelection(index)
        }

        hostInput.setText(state.host)
        portInput.setText(if (state.port in 1..65535) state.port.toString() else "")
        usernameInput.setText(state.username.orEmpty())
        passwordInput.setText(state.password.orEmpty())
        secretInput.setText(state.secret.orEmpty())

        updateFieldVisibility()
    }

    private fun readStateFromForm(showErrors: Boolean): ProxyConfigSnapshot? {
        if (!proxyEnabledSwitch.isChecked) {
            return ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)
        }

        val type = selectedType()
        val host = hostInput.text?.toString()?.trim().orEmpty()
        val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 0

        if (host.isBlank() || port !in 1..65535) {
            if (showErrors) showToast(getString(R.string.proxy_invalid_host_port))
            return null
        }

        val username = usernameInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        val password = passwordInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        val secret = secretInput.text?.toString()?.trim().orEmpty().ifBlank { null }

        if (type == ProxyType.MTPROTO && secret.isNullOrBlank()) {
            if (showErrors) showToast(getString(R.string.proxy_secret_required))
            return null
        }

        return ProxyConfigSnapshot(
            enabled = true,
            type = type,
            host = host,
            port = port,
            username = if (type == ProxyType.MTPROTO) null else username,
            password = if (type == ProxyType.MTPROTO) null else password,
            secret = if (type == ProxyType.MTPROTO) secret else null,
        )
    }

    private fun selectedType(): ProxyType {
        val raw = proxyTypeSpinner.selectedItem?.toString().orEmpty()
        return when (raw) {
            ProxyType.SOCKS5.name -> ProxyType.SOCKS5
            ProxyType.MTPROTO.name -> ProxyType.MTPROTO
            else -> ProxyType.HTTP
        }
    }

    private fun updateFieldVisibility() {
        val enabled = proxyEnabledSwitch.isChecked
        val type = selectedType()

        serverBlock.visibility = if (enabled) View.VISIBLE else View.GONE
        authBlock.visibility = if (enabled && type != ProxyType.MTPROTO) View.VISIBLE else View.GONE
        secretBlock.visibility = if (enabled && type == ProxyType.MTPROTO) View.VISIBLE else View.GONE
    }

    private fun startProxyHealthLoop() {
        proxyHealthJob?.cancel()
        proxyHealthJob = lifecycleScope.launch {
            while (isActive) {
                val current = readStateFromForm(showErrors = false) ?: AppRepositories.getProxyState()
                if (!current.enabled || current.type == ProxyType.DIRECT || current.host.isBlank() || current.port !in 1..65535) {
                    proxyHealthText.text = getString(R.string.proxy_health_disabled)
                    delay(PROXY_HEALTH_INTERVAL_MS)
                    continue
                }

                proxyHealthText.text = getString(R.string.proxy_health_checking)
                val pingMs = withTimeoutOrNull(PROXY_HEALTH_TIMEOUT_MS) {
                    measureProxyLatency(current)
                }

                proxyHealthText.text = if (pingMs != null) {
                    getString(R.string.proxy_health_ok, pingMs.roundToInt())
                } else {
                    getString(R.string.proxy_health_timeout)
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
        private const val PING_URL = "https://web.telegram.org/"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val PROXY_HEALTH_INTERVAL_MS = 10_000L
        private const val PROXY_HEALTH_TIMEOUT_MS = 60_000L
    }
}
