// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicReference

/**
 * MotoScreen v21 · "Google Maps inside our dash".
 *
 * Android Auto keeps running exactly as in plain AA mode (so Google Maps, voice and traffic all work
 * with the phone screen off). [AaCompositor] draws the AA picture into the window of the theme's
 * Google Maps widget, then lays this transparent dash layer — the rider's custom widgets — on top.
 * Rendered here at ~4 fps on its own thread; the compositor only uploads the newest bitmap.
 * Only used when the rider picked the MotoScreen dash *and* the theme has a Google Maps widget.
 */
object AaDashOverlay {
    @Volatile var enabled = false
        private set
    /** AA window in canvas pixels (top-left origin) + style; null = AA fills the whole canvas. */
    @Volatile var window: Rect? = null
        private set
    @Volatile var windowStyle = 0
        private set
    /** Set by the compositor: ask for a redraw on its GL thread. */
    @Volatile var requestRedraw: (() -> Unit)? = null

    private val latest = AtomicReference<Bitmap?>(null)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var app: Context? = null
    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    private var renderer: DashRenderer? = null
    private val spare = ArrayList<Bitmap>()
    private var fix: Location? = null
    private var fixAt = 0L

    /** Does this theme want Google Maps (Android Auto) inside the dash? */
    fun themeWantsAa(ctx: Context) = DashTheme.current(ctx).widgets.any { it.type == DashWidget.Type.AA }
    fun wanted(ctx: Context) = MotoDash.preferDash(ctx) && themeWantsAa(ctx)

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { fix = location; fixAt = System.currentTimeMillis() }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun enable(ctx: Context) {
        if (enabled) return
        app = ctx.applicationContext
        val t = HandlerThread("aa-dash-overlay").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        renderer = DashRenderer(app!!).also { it.aaLive = true }
        try {
            app!!.getSystemService(LocationManager::class.java)
                ?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, t.looper)
        } catch (e: Exception) { LogBus.log("[AA-DASH] no GPS: ${e.message}") }
        enabled = true
        h.post(tick)
        LogBus.log("[AA-DASH] on · theme '${DashTheme.current(app!!).name}'")
        requestRedraw?.invoke()
    }

    @Synchronized fun disable() {
        if (!enabled) return
        enabled = false
        try { app?.getSystemService(LocationManager::class.java)?.removeUpdates(listener) } catch (_: Exception) {}
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null; handler = null
        latest.set(null)
        window = null
        LogBus.log("[AA-DASH] off")
        requestRedraw?.invoke()
    }

    /** The bike canvas size, from the compositor's setOutput. */
    fun setCanvas(w: Int, h: Int) { canvasW = w; canvasH = h }

    /** Newest dash layer (null = nothing new since the last call). The caller owns it until [recycle]. */
    fun take(): Bitmap? = latest.getAndSet(null)

    fun recycle(b: Bitmap) { synchronized(spare) { if (spare.size < 2) spare += b } }

    private val tick = object : Runnable {
        override fun run() {
            if (!enabled) return
            try { renderOnce() } catch (e: Exception) { LogBus.log("[AA-DASH] render failed: ${e.message}") }
            handler?.postDelayed(this, 250)
        }
    }

    private fun renderOnce() {
        val ctx = app ?: return
        val w = canvasW; val h = canvasH
        if (w <= 0 || h <= 0) return
        val theme = DashTheme.current(ctx)
        val r = renderer ?: return
        val aa = theme.widgets.firstOrNull { it.type == DashWidget.Type.AA }
        if (aa == null || aa.style == 2) { window = null; windowStyle = 2 }
        else {
            val rf = r.windowRectOf(aa, w.toFloat(), h.toFloat())
            window = Rect(rf.left.toInt(), rf.top.toInt(), rf.right.toInt(), rf.bottom.toInt())
            windowStyle = aa.style
        }
        val bmp = synchronized(spare) { spare.removeLastOrNull() }?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val f = fix ?: TripRecorder.lastSeen
        val fresh = f != null && System.currentTimeMillis() - (if (fix != null) fixAt else f.time) < 5000
        val live = f?.toLive(fresh) ?: DashRenderer.Live(0, false)
        r.draw(c, w.toFloat(), h.toFloat(), theme, live, sample = false)
        latest.getAndSet(bmp)?.let { recycle(it) }
        requestRedraw?.invoke()
    }
}
