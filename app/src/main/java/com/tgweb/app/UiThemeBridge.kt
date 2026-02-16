package com.tgweb.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
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
        val color = runCatching {
            ContextCompat.getColor(activity, android.R.color.system_accent1_600)
        }.getOrNull()
        return color ?: readSurfaceColor(activity)
    }

    fun resolveSettingsSurfaceColor(activity: Activity): Int {
        val useDynamic = activity.getSharedPreferences(KeepAliveService.PREFS, Activity.MODE_PRIVATE)
            .getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, true)
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
        val textColor = if (darkText) Color.parseColor("#101820") else Color.parseColor("#EAF2FF")
        val hintColor = if (darkText) Color.parseColor("#66758A") else Color.parseColor("#9BB0C9")
        val bgColor = if (darkText) {
            ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.90f)
        } else {
            ColorUtils.blendARGB(surfaceColor, Color.BLACK, 0.35f)
        }
        root.setBackgroundColor(bgColor)

        fun apply(view: View) {
            when (view) {
                is EditText -> {
                    view.setTextColor(textColor)
                    view.setHintTextColor(hintColor)
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
