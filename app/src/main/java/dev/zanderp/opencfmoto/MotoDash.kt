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
    private var speedKmh = -1
    private var lastFixAt = 0L
    private val lm = context.getSystemService(LocationManager::class.java)

    private val cyan = Color.parseColor("#22E3D0")
    private val blue = Color.parseColor("#1AA3FF")
    private val track = Color.parseColor("#16212C")
    private val muted = Color.parseColor("#8AA0AA")
    private val white = Color.parseColor("#EAF4F7")
    private val amber = Color.parseColor("#FFC547")
    private val red = Color.parseColor("#FF5A4F")

    private val heavy: Typeface = font(R.font.archivo_black)
    private val medium: Typeface = font(R.font.archivo_semibold)
    private val body: Typeface = font(R.font.archivo_regular)
    private fun font(id: Int) = try { ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT_BOLD } catch (_: Exception) { Typeface.DEFAULT_BOLD }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    private val tick = object : Runnable {
        override fun run() { invalidate(); main.postDelayed(this, 200) }
    }

    @SuppressLint("MissingPermission")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
        catch (e: Exception) { log("[DASH] no GPS updates: ${e.message}") }
        main.post(tick)
        log("[DASH] cockpit attached")
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        super.onDetachedFromWindow()
    }

    override fun onLocationChanged(location: Location) {
        speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).toInt() else 0
        lastFixAt = System.currentTimeMillis()
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface, align: Paint.Align = Paint.Align.LEFT) {
        p.shader = null; p.style = Paint.Style.FILL
        p.color = color; p.textSize = size; p.typeface = tf; p.textAlign = align
        c.drawText(s, x, y, p)
    }

    private fun chip(c: Canvas, s: String, right: Float, top: Float, fg: Int, bg: Int, k: Float) {
        p.typeface = body; p.textSize = 20f * k
        val w = p.measureText(s) + 28f * k
        p.shader = null; p.style = Paint.Style.FILL; p.color = bg
        c.drawRoundRect(RectF(right - w, top, right, top + 40f * k), 14f * k, 14f * k, p)
        text(c, s, right - w / 2, top + 27f * k, 20f * k, fg, body, Paint.Align.CENTER)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val k = w / 1024f
        // Background: near-black with an accent glow from the bottom.
        p.shader = RadialGradient(w / 2, h * 1.2f, h * 1.1f, Color.parseColor("#0D3B3D"), Color.parseColor("#050709"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)

        // ---- centre: GPS speed gauge ----
        val cx = w / 2; val cy = h * 0.80f; val r = 172f * k
        arc.set(cx - r, cy - r, cx + r, cy + r)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 22f * k
        p.color = track
        c.drawArc(arc, 160f, 220f, false, p)
        val fresh = System.currentTimeMillis() - lastFixAt < 5000
        val spd = if (fresh) speedKmh.coerceAtLeast(0) else -1
        val frac = (spd.coerceAtLeast(0) / 140f).coerceIn(0f, 1f)
        if (frac > 0f) {
            p.shader = LinearGradient(cx - r, 0f, cx + r, 0f, cyan, blue, Shader.TileMode.CLAMP)
            c.drawArc(arc, 160f, 220f * frac, false, p)
        }
        p.shader = null
        text(c, if (spd < 0) "--" else spd.toString(), cx, h * 0.70f, 140f * k, white, heavy, Paint.Align.CENTER)
        text(c, if (spd < 0) context.getString(R.string.dash_gps_wait) else context.getString(R.string.dash_kmh), cx, h * 0.81f, 24f * k, muted, medium, Paint.Align.CENTER)

        // ---- right: clock, date, rain, fuel ----
        val now = Date()
        text(c, SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), w - 34f * k, 84f * k, 54f * k, white, heavy, Paint.Align.RIGHT)
        text(c, SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now), w - 34f * k, 114f * k, 18f * k, muted, body, Paint.Align.RIGHT)
        var chipTop = 136f * k
        RainCheck.lastAlert?.takeIf { System.currentTimeMillis() - it.second < 90 * 60_000L }?.let {
            chip(c, "🌧 " + it.first, w - 34f * k, chipTop, amber, Color.parseColor("#33FFC547"), k); chipTop += 52f * k
        }
        FuelPrice.cached(context)?.let { s ->
            val f = FuelPrice.primary(context)
            s.prices[f]?.today?.let { t ->
                val d = s.prices[f]?.tomorrow?.let { it - t } ?: 0.0
                val trend = when { d > 0.004 -> " ▲" + String.format(Locale.US, "%.2f", d); d < -0.004 -> " ▼" + String.format(Locale.US, "%.2f", -d); else -> "" }
                chip(c, "⛽ " + String.format(Locale.US, "%.2f", t) + " ฿" + trend, w - 34f * k, chipTop, if (d > 0.004) red else white, track, k)
            }
        }

        // ---- left: what's playing, else today's riding ----
        val mc = MotoDash.mediaController(context)
        val md = mc?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (!title.isNullOrBlank()) {
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val playing = mc?.playbackState?.state == PlaybackState.STATE_PLAYING
            text(c, (if (playing) "♪ " else "❚❚ ") + context.getString(R.string.dash_now_playing), 34f * k, 56f * k, 18f * k, cyan, medium)
            text(c, ellipsize(title, 260f * k, 30f * k, medium), 34f * k, 96f * k, 30f * k, white, medium)
            text(c, ellipsize(artist, 260f * k, 20f * k, body), 34f * k, 126f * k, 20f * k, muted, body)
            text(c, "⏮   " + (if (playing) "⏸" else "▶") + "   ⏭", 34f * k, 172f * k, 30f * k, white, body)
        } else {
            val live = TripLogger.current?.takeIf { it.recording }?.snapshot()
            val km = (live?.distanceMeters ?: 0.0) / 1000.0
            text(c, context.getString(R.string.dash_trip), 34f * k, 56f * k, 18f * k, cyan, medium)
            text(c, String.format(Locale.US, "%.1f", km), 34f * k, 112f * k, 56f * k, white, heavy)
            text(c, context.getString(R.string.dash_km), 34f * k, 140f * k, 20f * k, muted, body)
        }

        // ---- bottom strip: maintenance · phone battery ----
        val st = try { Maintenance.mostUrgent(context) } catch (_: Exception) { null }
        val left = st?.kmLeft
        if (st != null && left != null) {
            val s = if (left <= 0) context.getString(R.string.dash_maint_over, st.task.name)
                else context.getString(R.string.dash_maint_left, st.task.name, String.format(Locale.US, "%,.0f", left))
            text(c, "🔧 $s", 34f * k, h - 24f * k, 18f * k, if (left <= 0) red else muted, body)
        }
        val batt = try { context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { null }
        batt?.takeIf { it in 0..100 }?.let { text(c, "📱 $it%", w - 34f * k, h - 24f * k, 18f * k, if (it < 20) red else muted, body, Paint.Align.RIGHT) }
    }

    private fun ellipsize(s: String, maxW: Float, size: Float, tf: Typeface): String {
        p.textSize = size; p.typeface = tf
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
