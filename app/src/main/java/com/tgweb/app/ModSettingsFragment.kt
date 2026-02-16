package com.tgweb.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
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
        bindVersionPreference()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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

    companion object {
        private const val KEY_SCALE = "mod_interface_scale"
        private const val KEY_KEEP_ALIVE = "mod_keep_alive"
        private const val KEY_PUSH_PERMISSION = "mod_push_permission"
        private const val KEY_PROXY_SETTINGS = "mod_proxy_settings"
        private const val KEY_SESSION_TOOLS = "mod_session_tools"
        private const val KEY_DOWNLOADS_MANAGER = "mod_downloads_manager"
        private const val KEY_CUSTOMIZATION = "mod_customization"
        private const val KEY_BUNDLED_WEB = "mod_use_bundled_web"
        private const val KEY_VERSION = "mod_version"
    }
}
