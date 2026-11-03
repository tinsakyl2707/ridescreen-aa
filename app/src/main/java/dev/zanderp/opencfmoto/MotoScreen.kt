// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen TH (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Small MotoScreen TH helpers: the TH/EN slide toggle and "has this phone ever projected". */
object MotoScreen {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("motoscreen_th", Context.MODE_PRIVATE)

    /** True once the dash has shown Android Auto at least once (the first-time checklist then hides). */
    fun everConnected(ctx: Context): Boolean =
        prefs(ctx).getBoolean("ever_connected", false) ||
            // Riders upgrading from v4: a saved parking spot means they've connected before.
            try { Parking.get(ctx) != null } catch (_: Exception) { false }

    fun markConnected(ctx: Context) {
        if (!prefs(ctx).getBoolean("ever_connected", false)) prefs(ctx).edit().putBoolean("ever_connected", true).apply()
    }

    // ---- Look: light / dark / follow the phone, and the accent colour ----

    enum class Accent(val style: Int, val swatch: Long) {
        CYAN(R.style.ThemeOverlay_Ms_Accent_Cyan, 0xFF22E3D0),
        AMBER(R.style.ThemeOverlay_Ms_Accent_Amber, 0xFFFFB020),
        RED(R.style.ThemeOverlay_Ms_Accent_Red, 0xFFFF453A),
        LIME(R.style.ThemeOverlay_Ms_Accent_Lime, 0xFFA3E635),
    }

    fun accent(ctx: Context): Accent =
        runCatching { Accent.valueOf(prefs(ctx).getString("accent", Accent.CYAN.name)!!) }.getOrDefault(Accent.CYAN)

    /** Bumped on every accent change, so screens built with the old colour know to rebuild. */
    @Volatile var accentGeneration = 0

    fun setAccent(ctx: Context, a: Accent) {
        prefs(ctx).edit().putString("accent", a.name).apply()
        accentGeneration++
    }

    /** Called for every activity before onCreate (see OpenCfMotoApp). */
    fun applyAccent(activity: android.app.Activity) {
        try { activity.theme.applyStyle(accent(activity).style, true) } catch (_: Exception) {}
    }

    /** AppCompatDelegate night mode: FOLLOW_SYSTEM (default), NO (light) or YES (dark). */
    fun nightMode(ctx: Context): Int = prefs(ctx).getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setNightMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt("night", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyNightMode(ctx: Context) = AppCompatDelegate.setDefaultNightMode(nightMode(ctx))

    /** "th" or "en": the app's own choice if set, else the phone's language. */
    fun currentLang(): String {
        val app = AppCompatDelegate.getApplicationLocales()
        val tag = if (!app.isEmpty) app[0]?.language else Locale.getDefault().language
        return if (tag == "th") "th" else "en"
    }

    private fun setLang(tag: String) {
        langSwitching = true
        langSwitchAt = android.os.SystemClock.elapsedRealtime()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /**
     * v10 · True while screens are being rebuilt for a TH/EN switch: they skip what a fresh launch
     * does (auto-connect, update check, prompts) and fade in instead of popping. Cleared by
     * MainActivity's first resume afterwards, or after 5 s whatever happens.
     */
    @Volatile var langSwitching = false
        get() = field && android.os.SystemClock.elapsedRealtime() - langSwitchAt < 5_000
    @Volatile private var langSwitchAt = 0L

    fun langSwitchDone() { langSwitching = false }

    /** The rebuilt screen starts transparent and fades in (the old one faded out before the switch). */
    fun fadeIn(activity: android.app.Activity) {
        val content = activity.findViewById<View?>(android.R.id.content) ?: return
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * A pill with a sliding thumb behind "TH" and "EN". Tapping anywhere flips it: the thumb slides
     * across, then the app switches language (the activity recreates itself).
     */
    fun bindLangToggle(root: View, thumb: View, th: TextView, en: TextView) {
        val ctx = root.context
        val onText = ContextCompat.getColor(ctx, R.color.on_brand)
        val offText = ContextCompat.getColor(ctx, R.color.text_secondary)
        fun paint(lang: String, animate: Boolean) {
            val x = if (lang == "th") 0f else en.left.toFloat() - th.left.toFloat()
            if (animate) ObjectAnimator.ofFloat(thumb, View.TRANSLATION_X, x).apply {
                duration = 220; interpolator = DecelerateInterpolator(); start()
            } else thumb.translationX = x
            th.setTextColor(if (lang == "th") onText else offText)
            en.setTextColor(if (lang == "en") onText else offText)
        }
        val start = currentLang()
        root.post { paint(start, animate = false) }
        th.setTextColor(if (start == "th") onText else offText)
        en.setTextColor(if (start == "en") onText else offText)
        root.setOnClickListener {
            val next = if (currentLang() == "th") "en" else "th"
            paint(next, animate = true)
            root.isEnabled = false
            // Fade the whole screen out while the thumb slides, then switch: the rebuilt screen fades
            // back in (fadeIn), so the switch reads as one transition instead of a freeze.
            val content = (root.context as? android.app.Activity)?.findViewById<View?>(android.R.id.content)
            if (content != null) {
                content.animate().alpha(0f).setDuration(140).withEndAction {
                    setLang(next)
                    // Safety: if Android didn't rebuild this screen, don't leave it invisible.
                    content.postDelayed({ if (content.alpha < 1f) { content.alpha = 1f; root.isEnabled = true } }, 1500)
                }.start()
            } else {
                root.postDelayed({ setLang(next) }, 140)
            }
        }
    }
}
