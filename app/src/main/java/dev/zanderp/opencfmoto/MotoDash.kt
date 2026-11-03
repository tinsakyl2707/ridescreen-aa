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
    /** v20: where the live map must sit (null = no map widget) — [DashScreen] places the map view there. */
    var onMapArea: ((RectF?, Int) -> Unit)? = null
    var onFix: ((Location) -> Unit)? = null
    private var lastArea: RectF? = null
    private var lastStyle = -1

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
        onFix?.invoke(location)
        lastFixAt = System.currentTimeMillis()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val fresh = System.currentTimeMillis() - lastFixAt < 5000
        val live = fix?.toLive(fresh) ?: DashRenderer.Live(0, false)
        val theme = DashTheme.current(context)
        renderer.draw(c, w, h, theme, live, sample = false)
        val area = renderer.mapArea(theme, w, h)
        if (area?.first != lastArea || (area?.second ?: -1) != lastStyle) {
            lastArea = area?.first; lastStyle = area?.second ?: -1
            val cb = onMapArea
            if (cb != null) post { cb(area?.first, area?.second ?: 0) }
        }
    }
}

/**
 * v20 · The whole MotoScreen dash: the live map (osmdroid, the engine that encodes cleanly to the
 * bike) underneath, and [CockpitView] on top — which leaves the map's shape unpainted.
 */
@SuppressLint("ViewConstructor")
class DashScreen(context: Context, private val log: (String) -> Unit) : android.widget.FrameLayout(context) {
    private val mapHost = android.widget.FrameLayout(context).apply { visibility = GONE; clipToOutline = true }
    private val cockpit = CockpitView(context, log)
    private var map: DashMapController? = null
    private var lastFix: Location? = null

    init {
        addView(mapHost, LayoutParams(1, 1))
        addView(cockpit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cockpit.onMapArea = { r, st -> place(r, st) }
        cockpit.onFix = { loc -> lastFix = loc; follow(loc) }
    }

    private fun place(r: RectF?, style: Int) {
        if (r == null) { mapHost.visibility = GONE; return }
        if (map == null) {
            try {
                map = DashMapController(context, mapHost).also { m ->
                    m.onResume()
                    val start = lastFix ?: RideExtras.lastLocation(context, 24 * 3_600_000L)
                    if (start != null) m.setCenter(start.latitude, start.longitude, 16.0)
                }
                log("[DASH] map on")
            } catch (e: Exception) {
                log("[DASH] map failed: ${e.message}"); return
            }
        }
        val lp = mapHost.layoutParams as LayoutParams
        lp.width = r.width().toInt(); lp.height = r.height().toInt()
        lp.leftMargin = r.left.toInt(); lp.topMargin = r.top.toInt()
        mapHost.layoutParams = lp
        val k = width / 1024f
        mapHost.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                when (style) {
                    1 -> o.setOval(0, 0, v.width, v.height)
                    2 -> o.setRect(0, 0, v.width, v.height)
                    else -> o.setRoundRect(0, 0, v.width, v.height, 22f * k)
                }
            }
        }
        mapHost.invalidateOutline()
        mapHost.visibility = VISIBLE
        log("[DASH] map at ${r.left.toInt()},${r.top.toInt()} ${r.width().toInt()}x${r.height().toInt()} style=$style")
        lastFix?.let { follow(it) }
    }

    /** Heading-up while moving, zoom out as speed rises. */
    private fun follow(loc: Location) {
        val m = map ?: return
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        val moving = kmh > 5f && loc.hasBearing()
        val zoom = when { kmh < 25 -> 17.0; kmh < 60 -> 16.0; kmh < 90 -> 15.5; else -> 15.0 }
        try { m.follow(loc.latitude, loc.longitude, if (moving) loc.bearing else 0f, zoom, headingUp = moving, moving = moving) } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        try { map?.onPause(); map?.onDestroy() } catch (_: Exception) {}
        map = null
        super.onDetachedFromWindow()
    }
}
