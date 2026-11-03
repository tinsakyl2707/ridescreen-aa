// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.View
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MotoScreen v13 · our own dash screen ("Cockpit"), drawn by the app and sent to the bike instead of
 * Android Auto. Canvas-drawn (no XML, no theme attributes: the virtual-display context has no app
 * theme), redrawn ~5×/s. Data: GPS speed, clock, trip, maintenance, fuel price, rain, phone battery,
 * and what's playing (needs notification access — see [MsNotificationListener]).
 */
object MotoDash {
    /** True while the rider chose "MotoScreen dash" for this connection. Read by VideoPipeline. */
    @Volatile var active = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("motoscreen_th", Context.MODE_PRIVATE)
    /** Connect button target: false = Android Auto (default), true = our dash. */
    /** Default Android Auto; the rider can pick the MotoScreen dash on the home screen. */
    fun preferDash(ctx: Context) = prefs(ctx).getBoolean("prefer_dash", false)
    fun setPreferDash(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("prefer_dash", on).apply()

    /** The music app currently playing, through our notification listener. Null without access. */
    fun mediaController(ctx: Context): MediaController? = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(ctx, MsNotificationListener::class.java)
        val sessions = msm?.getActiveSessions(cn).orEmpty()
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    } catch (_: SecurityException) { null } catch (_: Exception) { null }

    fun hasMediaAccess(ctx: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(ctx, MsNotificationListener::class.java).flattenToString())
    }
}

/** Only here so Android lets us read the music apps' media sessions (and later, nav notifications). */
class MsNotificationListener : NotificationListenerService()

@SuppressLint("ViewConstructor")
class CockpitView(context: Context, private val log: (String) -> Unit) : View(context), LocationListener {
    private val main = Handler(Looper.getMainLooper())
    private var fix: Location? = null
    private var lastFixAt = 0L
    private val lm = context.getSystemService(LocationManager::class.java)
    /** v18: the layout comes from the rider's custom theme ([DashTheme]) — same renderer as the editor. */
    private val renderer = DashRenderer(context)

    private val tick = object : Runnable {
        override fun run() { invalidate(); main.postDelayed(this, 200) }
    }

    @SuppressLint("MissingPermission")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
        catch (e: Exception) { log("[DASH] no GPS updates: ${e.message}") }
        main.post(tick)
        log("[DASH] cockpit attached · theme '${DashTheme.current(context).name}' · ${DashTheme.current(context).widgets.size} widgets")
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        super.onDetachedFromWindow()
    }

    override fun onLocationChanged(location: Location) {
        fix = location
        lastFixAt = System.currentTimeMillis()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val fresh = System.currentTimeMillis() - lastFixAt < 5000
        val live = fix?.toLive(fresh) ?: DashRenderer.Live(0, false)
        renderer.draw(c, w, h, DashTheme.current(context), live, sample = false)
    }
}
