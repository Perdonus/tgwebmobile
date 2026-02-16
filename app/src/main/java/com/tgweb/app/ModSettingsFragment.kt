package com.tgweb.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.tgweb.core.data.AppRepositories

class ModSettingsFragment : PreferenceFragmentCompat() {
    private val runtimePrefs by lazy {
        requireContext().getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = KeepAliveService.PREFS
        setPreferencesFromResource(R.xml.mod_settings, rootKey)

        bindScalePreference()
        bindKeepAlivePreference()
        bindBundledWebPreference()
        bindPushPermissionPreference()
        bindProxyEntryPreference()
        bindSessionToolsPreference()
        bindDownloadsEntryPreference()
        bindCustomizationEntryPreference()
        bindAuthorChannelPreference()
        bindVersionPreference()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyExpressiveSettingsStyle()
        bindVersionLongPressDebugEntry()
    }

    override fun onResume() {
        super.onResume()
        updatePushPermissionSummary()
    }

    private fun bindScalePreference() {
        val pref = findPreference<SeekBarPreference>(KEY_SCALE) ?: return
        pref.min = 75
        pref.max = 140
        pref.showSeekBarValue = true
        val current = runtimePrefs.getInt(KeepAliveService.KEY_UI_SCALE_PERCENT, 100).coerceIn(75, 140)
        if (pref.value != current) {
            pref.value = current
        }
        pref.summary = getString(R.string.mod_scale_summary, current)
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? Int)?.coerceIn(75, 140) ?: return@setOnPreferenceChangeListener false
            runtimePrefs.edit().putInt(KeepAliveService.KEY_UI_SCALE_PERCENT, value).apply()
            AppRepositories.postInterfaceScaleState(value)
            preference.summary = getString(R.string.mod_scale_summary, value)
            true
        }
    }

    private fun bindKeepAlivePreference() {
        val pref = findPreference<SwitchPreferenceCompat>(KEY_KEEP_ALIVE) ?: return
        pref.isChecked = KeepAliveService.isEnabled(requireContext())
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            KeepAliveService.setEnabled(requireContext(), enabled)
            if (enabled) {
                KeepAliveService.start(requireContext())
            } else {
                KeepAliveService.stop(requireContext())
            }
            AppRepositories.postKeepAliveState(enabled)
            true
        }
    }

    private fun bindBundledWebPreference() {
        val pref = findPreference<SwitchPreferenceCompat>(KEY_BUNDLED_WEB) ?: return
        pref.isChecked = runtimePrefs.getBoolean(KeepAliveService.KEY_USE_BUNDLED_WEBK, true)
        pref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            runtimePrefs.edit().putBoolean(KeepAliveService.KEY_USE_BUNDLED_WEBK, enabled).apply()
            true
        }
    }

    private fun bindPushPermissionPreference() {
        val pref = findPreference<Preference>(KEY_PUSH_PERMISSION) ?: return
        pref.setOnPreferenceClickListener {
            (activity as? ModSettingsActivity)?.requestPushPermissionFromSettings()
            true
        }
    }

    private fun bindProxyEntryPreference() {
        val pref = findPreference<Preference>(KEY_PROXY_SETTINGS) ?: return
        pref.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ProxySettingsActivity::class.java))
            true
        }
    }

    private fun bindDownloadsEntryPreference() {
        val pref = findPreference<Preference>(KEY_DOWNLOADS_MANAGER) ?: return
        pref.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DownloadsActivity::class.java))
            true
        }
    }

    private fun bindSessionToolsPreference() {
        val pref = findPreference<Preference>(KEY_SESSION_TOOLS) ?: return
        pref.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SessionToolsActivity::class.java))
            true
        }
    }

    private fun bindCustomizationEntryPreference() {
        val pref = findPreference<Preference>(KEY_CUSTOMIZATION) ?: return
        pref.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), CustomizationActivity::class.java))
            true
        }
    }

    private fun bindAuthorChannelPreference() {
        val pref = findPreference<Preference>(KEY_AUTHOR_CHANNEL) ?: return
        pref.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_CHANNEL_USERNAME, "plugin_ai")
                },
            )
            true
        }
    }

    private fun bindVersionPreference() {
        val versionName = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrDefault("unknown")
        findPreference<Preference>(KEY_VERSION)?.summary = versionName
    }

    private fun bindVersionLongPressDebugEntry() {
        val recycler = listView ?: return
        val versionPreference = findPreference<Preference>(KEY_VERSION) ?: return
        recycler.post {
            val adapter = recycler.adapter as? PreferenceGroupAdapter ?: return@post
            val targetPosition = adapter.getPreferenceAdapterPosition(versionPreference)
            if (targetPosition == RecyclerView.NO_POSITION) return@post

            val applyLongPress: (View) -> Unit = { row ->
                row.setOnLongClickListener {
                    startActivity(Intent(requireContext(), DebugToolsActivity::class.java))
                    true
                }
            }

            recycler.findViewHolderForAdapterPosition(targetPosition)?.itemView?.let(applyLongPress)
            recycler.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        val holder = recycler.getChildViewHolder(view)
                        if (holder.adapterPosition == targetPosition) {
                            applyLongPress(view)
                        }
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                },
            )
        }
    }

    private fun updatePushPermissionSummary() {
        val pref = findPreference<Preference>(KEY_PUSH_PERMISSION) ?: return
        pref.summary = if (isPushPermissionGranted()) {
            getString(R.string.mod_push_permission_granted)
        } else {
            getString(R.string.mod_push_permission_missing)
        }
    }

    private fun isPushPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun applyExpressiveSettingsStyle() {
        val recycler = listView ?: return
        recycler.clipToPadding = false
        recycler.setPadding(dp(10), dp(8), dp(10), dp(20))

        val surface = UiThemeBridge.resolveSettingsSurfaceColor(requireActivity())
        val textPrimary = if (UiThemeBridge.isLight(surface)) Color.parseColor("#152235") else Color.parseColor("#EAF3FF")
        val textMuted = if (UiThemeBridge.isLight(surface)) Color.parseColor("#5D7088") else Color.parseColor("#9CB3CE")
        val cardColor = if (UiThemeBridge.isLight(surface)) {
            ColorUtils.blendARGB(surface, Color.WHITE, 0.22f)
        } else {
            ColorUtils.blendARGB(surface, Color.WHITE, 0.08f)
        }
        val strokeColor = if (UiThemeBridge.isLight(surface)) {
            ColorUtils.blendARGB(surface, Color.BLACK, 0.10f)
        } else {
            ColorUtils.blendARGB(surface, Color.WHITE, 0.14f)
        }

        fun applyRowStyle(row: View) {
            val title = row.findViewById<TextView>(android.R.id.title)
            val summary = row.findViewById<TextView>(android.R.id.summary)
            val isCategory = !row.isClickable && summary == null

            if (isCategory) {
                row.background = null
                (row.layoutParams as? RecyclerView.LayoutParams)?.let { lp ->
                    lp.setMargins(dp(2), dp(14), dp(2), dp(2))
                    row.layoutParams = lp
                }
                title?.apply {
                    setTextColor(textMuted)
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    letterSpacing = 0.03f
                }
                return
            }

            val shape = GradientDrawable().apply {
                cornerRadius = dpF(18f)
                setColor(cardColor)
                setStroke(dp(1), strokeColor)
            }
            row.background = shape
            row.setPadding(dp(16), dp(12), dp(16), dp(12))
            (row.layoutParams as? RecyclerView.LayoutParams)?.let { lp ->
                lp.setMargins(dp(2), dp(6), dp(2), dp(6))
                row.layoutParams = lp
            }
            title?.setTextColor(textPrimary)
            summary?.setTextColor(textMuted)
        }

        recycler.post {
            for (i in 0 until recycler.childCount) {
                applyRowStyle(recycler.getChildAt(i))
            }
        }
        recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyRowStyle(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    private fun dpF(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )
    }

    companion object {
        private const val KEY_SCALE = "mod_interface_scale"
        private const val KEY_KEEP_ALIVE = "mod_keep_alive"
        private const val KEY_PUSH_PERMISSION = "mod_push_permission"
        private const val KEY_PROXY_SETTINGS = "mod_proxy_settings"
        private const val KEY_SESSION_TOOLS = "mod_session_tools"
        private const val KEY_DOWNLOADS_MANAGER = "mod_downloads_manager"
        private const val KEY_CUSTOMIZATION = "mod_customization"
        private const val KEY_BUNDLED_WEB = "mod_use_bundled_web"
        private const val KEY_AUTHOR_CHANNEL = "mod_author_channel"
        private const val KEY_VERSION = "mod_version"
    }
}
