package com.tgweb.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

object UiThemeBridge {
    private const val DEFAULT_COLOR = "#0E1621"

    fun readSurfaceColor(activity: Activity): Int {
        val raw = activity.getSharedPreferences(KeepAliveService.PREFS, Activity.MODE_PRIVATE)
            .getString(KeepAliveService.KEY_STATUS_BAR_COLOR, DEFAULT_COLOR)
            .orEmpty()
        return runCatching { Color.parseColor(raw) }.getOrDefault(Color.parseColor(DEFAULT_COLOR))
    }

    fun readDynamicSurfaceColor(activity: Activity): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return readSurfaceColor(activity)
        }
        val base = readSurfaceColor(activity)
        val accent = runCatching {
            ContextCompat.getColor(activity, android.R.color.system_accent1_500)
        }.getOrNull() ?: return base
        val blendAmount = if (isLight(base)) 0.16f else 0.22f
        return ColorUtils.blendARGB(base, accent, blendAmount)
    }

    fun resolveSettingsSurfaceColor(activity: Activity): Int {
        val useDynamic = activity.getSharedPreferences(KeepAliveService.PREFS, Activity.MODE_PRIVATE)
            .getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, false)
        return if (useDynamic) readDynamicSurfaceColor(activity) else readSurfaceColor(activity)
    }

    fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) >= 0.62

    fun applyWindowColors(activity: Activity, surfaceColor: Int) {
        activity.window.statusBarColor = surfaceColor
        activity.window.navigationBarColor = surfaceColor
        activity.actionBar?.setBackgroundDrawable(ColorDrawable(surfaceColor))
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar
            ?.setBackgroundDrawable(ColorDrawable(surfaceColor))
    }

    fun applyContentContrast(root: View, surfaceColor: Int) {
        val darkText = isLight(surfaceColor)
        val textColor = if (darkText) Color.parseColor("#142132") else Color.parseColor("#EAF3FF")
        val hintColor = if (darkText) Color.parseColor("#5E7289") else Color.parseColor("#A2B5CD")
        val bgColor = if (darkText) {
            ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.10f)
        } else {
            ColorUtils.blendARGB(surfaceColor, Color.BLACK, 0.18f)
        }
        root.setBackgroundColor(bgColor)

        fun apply(view: View) {
            when (view) {
                is EditText -> {
                    view.setTextColor(textColor)
                    view.setHintTextColor(hintColor)
                }
                is Button -> {
                    // Keep button text colors from the active theme for better contrast.
                }
                is TextView -> {
                    view.setTextColor(textColor)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    apply(view.getChildAt(index))
                }
            }
        }
        apply(root)
    }
}
