package com.tgweb.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

object UiThemeBridge {
    private const val DEFAULT_COLOR = "#0E1621"

    data class SettingsPalette(
        val background: Int,
        val surface: Int,
        val surfaceContainer: Int,
        val surfaceContainerHigh: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outlineVariant: Int,
        val primary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val isLight: Boolean,
    )

    fun prepareSettingsTheme(activity: Activity) {
        val surfaceColor = resolveSettingsSurfaceColor(activity)
        activity.setTheme(
            if (isLight(surfaceColor)) {
                R.style.Theme_TGWeb_Settings_Light
            } else {
                R.style.Theme_TGWeb_Settings_Dark
            },
        )
        if (activity.getSharedPreferences(KeepAliveService.PREFS, Activity.MODE_PRIVATE)
                .getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, false)
        ) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

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

    fun resolveSettingsPalette(activity: Activity): SettingsPalette {
        val fallbackSurface = resolveSettingsSurfaceColor(activity)
        val surface = resolveThemeColor(activity, com.google.android.material.R.attr.colorSurface, fallbackSurface)
        val background = resolveThemeColor(activity, android.R.attr.colorBackground, surface)
        val onSurface = resolveThemeColor(activity, com.google.android.material.R.attr.colorOnSurface, readableOn(surface))
        val surfaceContainer = resolveThemeColor(
            activity,
            com.google.android.material.R.attr.colorSurfaceContainer,
            blendForContainer(surface),
        )
        val surfaceContainerHigh = resolveThemeColor(
            activity,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            blendForContainer(surface, high = true),
        )
        val onSurfaceVariant = resolveThemeColor(
            activity,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ColorUtils.setAlphaComponent(onSurface, 190),
        )
        val outlineVariant = resolveThemeColor(
            activity,
            com.google.android.material.R.attr.colorOutlineVariant,
            ColorUtils.setAlphaComponent(onSurface, 44),
        )
        val primary = resolveThemeColor(activity, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#3390EC"))
        val primaryContainer = resolveThemeColor(
            activity,
            com.google.android.material.R.attr.colorPrimaryContainer,
            ColorUtils.blendARGB(surface, primary, if (isLight(surface)) 0.18f else 0.28f),
        )
        val onPrimaryContainer = resolveThemeColor(
            activity,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            readableOn(primaryContainer),
        )
        return SettingsPalette(
            background = background,
            surface = surface,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            outlineVariant = outlineVariant,
            primary = primary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            isLight = isLight(surface),
        )
    }

    fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) >= 0.62

    fun applyWindowColors(activity: Activity, palette: SettingsPalette = resolveSettingsPalette(activity)) {
        activity.window.statusBarColor = palette.surface
        activity.window.navigationBarColor = palette.surface
        activity.actionBar?.setBackgroundDrawable(ColorDrawable(palette.surface))
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar
            ?.setBackgroundDrawable(ColorDrawable(palette.surface))

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = palette.isLight
        controller.isAppearanceLightNavigationBars = palette.isLight
    }

    fun applyContentContrast(root: View, palette: SettingsPalette) {
        root.setBackgroundColor(palette.background)
    }

    fun styleListView(listView: ListView, palette: SettingsPalette) {
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.cacheColorHint = Color.TRANSPARENT
        listView.divider = ColorDrawable(Color.TRANSPARENT)
        listView.dividerHeight = dp(listView.context, 8)
        listView.setPadding(0, dp(listView.context, 8), 0, dp(listView.context, 8))
        listView.clipToPadding = false
    }

    fun styleTwoLineListRow(
        context: Context,
        rowView: View,
        titleView: TextView,
        subtitleView: TextView,
        palette: SettingsPalette,
        activated: Boolean,
    ) {
        val fillColor = if (activated) palette.primaryContainer else palette.surfaceContainer
        titleView.setTextColor(if (activated) palette.onPrimaryContainer else palette.onSurface)
        subtitleView.setTextColor(if (activated) palette.onPrimaryContainer else palette.onSurfaceVariant)
        titleView.maxLines = 2
        subtitleView.maxLines = 3
        val contentPaddingHorizontal = dp(context, 20)
        val contentPaddingVertical = dp(context, 16)
        rowView.minimumHeight = dp(context, 84)
        rowView.setPadding(contentPaddingHorizontal, contentPaddingVertical, contentPaddingHorizontal, contentPaddingVertical)
        rowView.background = createSelectableGroupBackground(
            context = context,
            fillColor = fillColor,
            strokeColor = palette.outlineVariant,
            rippleColor = ColorUtils.setAlphaComponent(palette.primary, if (palette.isLight) 26 else 52),
            topRounded = true,
            bottomRounded = true,
        )
    }

    fun createSelectableGroupBackground(
        context: Context,
        fillColor: Int,
        strokeColor: Int,
        rippleColor: Int,
        topRounded: Boolean,
        bottomRounded: Boolean,
    ): RippleDrawable {
        val radius = dp(context, 24).toFloat()
        val smallRadius = dp(context, 6).toFloat()
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (topRounded) radius else smallRadius)
            .setTopRightCornerSize(if (topRounded) radius else smallRadius)
            .setBottomLeftCornerSize(if (bottomRounded) radius else smallRadius)
            .setBottomRightCornerSize(if (bottomRounded) radius else smallRadius)
            .build()
        val content = MaterialShapeDrawable(shape).apply {
            this.fillColor = ColorStateList.valueOf(fillColor)
            strokeWidth = dp(context, 1).toFloat()
            this.strokeColor = ColorStateList.valueOf(strokeColor)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }

    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        return runCatching {
            MaterialColors.getColor(context, attr, fallback)
        }.getOrDefault(fallback)
    }

    private fun readableOn(background: Int): Int {
        return if (isLight(background)) Color.parseColor("#172233") else Color.parseColor("#EAF2FF")
    }

    private fun blendForContainer(surface: Int, high: Boolean = false): Int {
        val target = if (isLight(surface)) Color.WHITE else Color.BLACK
        val amount = if (high) {
            if (isLight(surface)) 0.24f else 0.30f
        } else {
            if (isLight(surface)) 0.14f else 0.20f
        }
        return ColorUtils.blendARGB(surface, target, amount)
    }
}
