package com.samrat.cardboardhands

import android.content.Context

/** Keeps the light/dark home preference without bundling third-party icon artwork. */
object IconPack {
    enum class Theme { LIGHT, DARK }
    private const val PREFS = "icon_pack"

    fun theme(context: Context): Theme =
        runCatching { Theme.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("theme", Theme.LIGHT.name)!!) }.getOrDefault(Theme.LIGHT)

    fun setTheme(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("theme", theme.name).apply()
    }
}
