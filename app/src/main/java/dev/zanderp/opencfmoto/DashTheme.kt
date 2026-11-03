// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import androidx.core.content.res.ResourcesCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MotoScreen v18 · a custom dash layout: background + freely placed widgets.
 * Positions are normalised (0..1 of the dash canvas, widget centre) so one theme fits any panel.
 */
data class DashWidget(val type: Type, var x: Float, var y: Float, var size: Int = 1, var text: String = "") {
    enum class Type(val label: Int) {
        GAUGE(R.string.dw_gauge), SPEED(R.string.dw_speed), CLOCK(R.string.dw_clock), DATE(R.string.dw_date),
        TRIP(R.string.dw_trip), RIDE_TIME(R.string.dw_ride_time), AVG(R.string.dw_avg), FUEL(R.string.dw_fuel),
        RAIN(R.string.dw_rain), MAINT(R.string.dw_maint), BATTERY(R.string.dw_battery), TEXT(R.string.dw_text),
    }
    fun json() = JSONObject().put("t", type.name).put("x", x.toDouble()).put("y", y.toDouble()).put("s", size).put("txt", text)
    companion object {
        fun from(o: JSONObject): DashWidget? = runCatching {
            DashWidget(Type.valueOf(o.getString("t")), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optInt("s", 1), o.optString("txt"))
        }.getOrNull()
    }
}

data class DashTheme(
    var name: String,
    /** "glow" (dark gradient), "art" (the MotoScreen rider art), "photo" (rider's own picture), "black". */
    var bg: String = "glow",
    var dim: Float = 0.45f,
    val widgets: MutableList<DashWidget> = mutableListOf(),
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("bg", bg).put("dim", dim.toDouble())
        .put("w", JSONArray().apply { widgets.forEach { put(it.json()) } })

    fun copyDeep() = DashTheme(name, bg, dim, widgets.map { it.copy() }.toMutableList())

    companion object {
        fun from(o: JSONObject): DashTheme {
            val list = mutableListOf<DashWidget>()
            o.optJSONArray("w")?.let { a -> for (i in 0 until a.length()) DashWidget.from(a.getJSONObject(i))?.let { list += it } }
            return DashTheme(o.optString("name", "MotoScreen"), o.optString("bg", "glow"), o.optDouble("dim", 0.45).toFloat(), list)
        }

        /** The Cockpit layout, as widgets — the starting point and "reset". */
        fun cockpit(ctx: Context) = DashTheme(ctx.getString(R.string.dt_default_name), "glow", 0.45f, mutableListOf(
            DashWidget(DashWidget.Type.GAUGE, 0.5f, 0.62f, 1),
            DashWidget(DashWidget.Type.TRIP, 0.11f, 0.22f, 1),
            DashWidget(DashWidget.Type.CLOCK, 0.89f, 0.17f, 1),
            DashWidget(DashWidget.Type.DATE, 0.89f, 0.29f, 1),
            DashWidget(DashWidget.Type.RAIN, 0.89f, 0.40f, 1),
            DashWidget(DashWidget.Type.FUEL, 0.89f, 0.52f, 1),
            DashWidget(DashWidget.Type.MAINT, 0.22f, 0.93f, 0),
            DashWidget(DashWidget.Type.BATTERY, 0.92f, 0.93f, 0),
        ))

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("motoscreen_dash", Context.MODE_PRIVATE)

        @Volatile private var cached: DashTheme? = null
        @Volatile var generation = 0

        fun current(ctx: Context): DashTheme {
            cached?.let { return it }
            val t = prefs(ctx).getString("current", null)?.let { runCatching { from(JSONObject(it)) }.getOrNull() } ?: cockpit(ctx)
            cached = t
            return t
        }

        fun setCurrent(ctx: Context, t: DashTheme) {
            cached = t.copyDeep(); generation++
            prefs(ctx).edit().putString("current", t.json().toString()).apply()
        }

        fun saved(ctx: Context): List<DashTheme> = runCatching {
            val a = JSONArray(prefs(ctx).getString("saved", "[]"))
            (0 until a.length()).map { from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun save(ctx: Context, t: DashTheme) {
            val list = saved(ctx).filter { it.name != t.name } + t.copyDeep()
            prefs(ctx).edit().putString("saved", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
        }

        fun delete(ctx: Context, name: String) {
            val list = saved(ctx).filter { it.name != name }
            prefs(ctx).edit().putString("saved", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
        }

        fun photoFile(ctx: Context) = File(ctx.filesDir, "dash_bg.jpg")
    }
}

/**
 * Draws a [DashTheme] — shared by the dash itself ([CockpitView]) and the editor preview, so what
 * the rider arranges is exactly what the bike shows. [sample] = editor without live data: show
 * believable placeholder values so every widget is visible.
 */
class DashRenderer(private val ctx: Context) {
    data class Live(val speedKmh: Int, val hasFix: Boolean)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val heavy: Typeface = font(R.font.archivo_black)
    private val medium: Typeface = font(R.font.archivo_semibold)
    private val body: Typeface = font(R.font.archivo_regular)
    private fun font(id: Int) = try { ResourcesCompat.getFont(ctx, id) ?: Typeface.DEFAULT_BOLD } catch (_: Exception) { Typeface.DEFAULT_BOLD }

    private val white = Color.parseColor("#EAF4F7")
    private val muted = Color.parseColor("#9FB3BC")
    private val track = Color.parseColor("#33FFFFFF")
    private val amber = Color.parseColor("#FFC547")
    private val red = Color.parseColor("#FF5A4F")
    private fun accent() = MotoScreen.accent(ctx).swatch.toInt()
    private val accent2 = Color.parseColor("#1AA3FF")

    private var bgKey: String? = null
    private var bgBitmap: Bitmap? = null

    /** Bounds of each widget from the last draw, for the editor's hit-testing. */
    val bounds = ArrayList<Pair<DashWidget, RectF>>()

    private fun background(theme: DashTheme): Bitmap? {
        val key = when (theme.bg) {
            "photo" -> DashTheme.photoFile(ctx).let { "photo@" + it.lastModified() }
            "art" -> "art"
            else -> null
        }
        if (key == bgKey) return bgBitmap
        bgKey = key
        bgBitmap = when (theme.bg) {
            "photo" -> runCatching { BitmapFactory.decodeFile(DashTheme.photoFile(ctx).absolutePath) }.getOrNull()
            "art" -> runCatching { BitmapFactory.decodeResource(ctx.resources, R.drawable.ms_hero_default) }.getOrNull()
            else -> null
        }
        return bgBitmap
    }

    fun draw(c: Canvas, w: Float, h: Float, theme: DashTheme, live: Live?, sample: Boolean) {
        val k = w / 1024f
        p.shader = null; p.style = Paint.Style.FILL
        val bmp = background(theme)
        if (bmp != null) {
            // centre-crop
            val s = maxOf(w / bmp.width, h / bmp.height)
            val sw = (w / s).toInt(); val sh = (h / s).toInt()
            val sx = (bmp.width - sw) / 2; val sy = (bmp.height - sh) / 2
            c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), p)
            p.color = Color.argb((theme.dim.coerceIn(0f, 0.9f) * 255).toInt(), 0, 0, 0)
            c.drawRect(0f, 0f, w, h, p)
        } else if (theme.bg == "black") {
            p.color = Color.BLACK; c.drawRect(0f, 0f, w, h, p)
        } else {
            p.shader = RadialGradient(w / 2, h * 1.2f, h * 1.1f, Color.parseColor("#0D3B3D"), Color.parseColor("#050709"), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
        }
        bounds.clear()
        for (wd in theme.widgets) bounds += wd to drawWidget(c, wd, w, h, k, live, sample)
    }

    private fun scale(size: Int) = when (size) { 0 -> 0.75f; 2 -> 1.4f; else -> 1f }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface): RectF {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.typeface = tf
        p.textAlign = Paint.Align.CENTER
        p.setShadowLayer(size * 0.12f, 0f, 0f, Color.argb(170, 0, 0, 0))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
        val tw = p.measureText(s)
        return RectF(x - tw / 2, y - size * 0.85f, x + tw / 2, y + size * 0.2f)
    }

    private fun chip(c: Canvas, s: String, x: Float, y: Float, size: Float, fg: Int, bg: Int): RectF {
        p.typeface = body; p.textSize = size
        val tw = p.measureText(s) + size * 1.4f; val th = size * 2f
        val r = RectF(x - tw / 2, y - th / 2, x + tw / 2, y + th / 2)
        p.shader = null; p.style = Paint.Style.FILL; p.color = bg
        c.drawRoundRect(r, th * 0.35f, th * 0.35f, p)
        p.color = fg; p.textAlign = Paint.Align.CENTER
        c.drawText(s, x, y + size * 0.36f, p)
        return r
    }

    private fun drawWidget(c: Canvas, wd: DashWidget, w: Float, h: Float, k: Float, live: Live?, sample: Boolean): RectF {
        val s = scale(wd.size) * k
        val x = wd.x * w; val y = wd.y * h
        val trip = TripLogger.current?.takeIf { it.recording }?.snapshot()
        val loc = Locale.getDefault()
        val speed = live?.takeIf { it.hasFix }?.speedKmh ?: if (sample) 68 else -1
        return when (wd.type) {
            DashWidget.Type.GAUGE -> {
                val r = 160f * s
                val arc = RectF(x - r, y - r * 0.55f, x + r, y + r * 1.45f)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 20f * s; p.shader = null; p.color = track
                c.drawArc(arc, 160f, 220f, false, p)
                val frac = (speed.coerceAtLeast(0) / 140f).coerceIn(0f, 1f)
                if (frac > 0) {
                    p.shader = LinearGradient(x - r, 0f, x + r, 0f, accent(), accent2, Shader.TileMode.CLAMP)
                    c.drawArc(arc, 160f, 220f * frac, false, p); p.shader = null
                }
                p.style = Paint.Style.FILL
                txt(c, if (speed < 0) "--" else speed.toString(), x, y + 40f * s, 130f * s, white, heavy)
                txt(c, ctx.getString(if (speed < 0) R.string.dash_gps_wait else R.string.dash_kmh), x, y + 78f * s, 22f * s, muted, medium)
                RectF(x - r - 10 * s, y - r * 0.55f - 10 * s, x + r + 10 * s, y + 90f * s)
            }
            DashWidget.Type.SPEED -> {
                val a = txt(c, if (speed < 0) "--" else speed.toString(), x, y + 50f * s, 170f * s, white, heavy)
                val b = txt(c, ctx.getString(R.string.dash_kmh), x, y + 84f * s, 24f * s, accent(), medium)
                RectF(a).apply { union(b) }
            }
            DashWidget.Type.CLOCK -> txt(c, SimpleDateFormat("HH:mm", loc).format(Date()), x, y + 20f * s, 54f * s, white, heavy)
            DashWidget.Type.DATE -> txt(c, SimpleDateFormat("EEE d MMM", loc).format(Date()), x, y + 7f * s, 20f * s, muted, body)
            DashWidget.Type.TRIP -> {
                val km = (trip?.distanceMeters ?: if (sample) 12400.0 else 0.0) / 1000.0
                val a = txt(c, ctx.getString(R.string.dash_trip), x, y - 26f * s, 18f * s, accent(), medium)
                val b = txt(c, String.format(Locale.US, "%.1f", km), x, y + 22f * s, 52f * s, white, heavy)
                val d = txt(c, ctx.getString(R.string.dash_km), x, y + 46f * s, 18f * s, muted, body)
                RectF(a).apply { union(b); union(d) }
            }
            DashWidget.Type.RIDE_TIME -> {
                val ms = trip?.movingTimeMs ?: if (sample) 21 * 60_000L else 0L
                val a = txt(c, ctx.getString(R.string.dw_ride_time), x, y - 22f * s, 18f * s, accent(), medium)
                val b = txt(c, String.format(Locale.US, "%d:%02d", ms / 3_600_000, (ms / 60_000) % 60), x, y + 22f * s, 44f * s, white, heavy)
                RectF(a).apply { union(b) }
            }
            DashWidget.Type.AVG -> {
                val avg = trip?.let { t -> if (t.movingTimeMs > 0) (t.distanceMeters / 1000.0) / (t.movingTimeMs / 3_600_000.0) else 0.0 } ?: if (sample) 38.0 else 0.0
                val a = txt(c, ctx.getString(R.string.dw_avg), x, y - 22f * s, 18f * s, accent(), medium)
                val b = txt(c, String.format(Locale.US, "%.0f", avg), x, y + 22f * s, 44f * s, white, heavy)
                RectF(a).apply { union(b) }
            }
            DashWidget.Type.FUEL -> {
                val snap = FuelPrice.cached(ctx)
                val f = FuelPrice.primary(ctx)
                val pr = snap?.prices?.get(f)
                val t = pr?.today
                val label = when {
                    t != null -> {
                        val d = pr?.tomorrow?.let { it - t } ?: 0.0
                        val tr = when { d > 0.004 -> " ▲" + String.format(Locale.US, "%.2f", d); d < -0.004 -> " ▼" + String.format(Locale.US, "%.2f", -d); else -> "" }
                        "⛽ " + ctx.getString(f.label) + " " + String.format(Locale.US, "%.2f", t) + " ฿" + tr
                    }
                    sample -> "⛽ E20 33.50 ฿"
                    else -> null
                }
                if (label == null) RectF(x - 40 * s, y - 15 * s, x + 40 * s, y + 15 * s)
                else chip(c, label, x, y, 20f * s, white, Color.parseColor("#B3101820"))
            }
            DashWidget.Type.RAIN -> {
                val alert = RainCheck.lastAlert?.takeIf { System.currentTimeMillis() - it.second < 90 * 60_000L }?.first
                val label = alert ?: if (sample) ctx.getString(R.string.dw_rain_sample) else null
                if (label == null) RectF(x - 40 * s, y - 15 * s, x + 40 * s, y + 15 * s)
                else chip(c, "🌧 $label", x, y, 20f * s, amber, Color.parseColor("#B3332A10"))
            }
            DashWidget.Type.MAINT -> {
                val st = try { Maintenance.mostUrgent(ctx) } catch (_: Exception) { null }
                val left = st?.kmLeft
                val label = when {
                    st != null && left != null && left <= 0 -> ctx.getString(R.string.dash_maint_over, st.task.name)
                    st != null && left != null -> ctx.getString(R.string.dash_maint_left, st.task.name, String.format(Locale.US, "%,.0f", left))
                    sample -> ctx.getString(R.string.dash_maint_left, ctx.getString(R.string.maint_oil_engine), "2,450")
                    else -> ""
                }
                txt(c, if (label.isEmpty()) "" else "🔧 $label", x, y + 7f * s, 20f * s, if (left != null && left <= 0) red else muted, body)
                    .let { if (it.width() < 20) RectF(x - 40 * s, y - 15 * s, x + 40 * s, y + 15 * s) else it }
            }
            DashWidget.Type.BATTERY -> {
                val b = try { ctx.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { null }
                txt(c, "📱 ${b ?: 82}%", x, y + 7f * s, 20f * s, if ((b ?: 100) < 20) red else muted, body)
            }
            DashWidget.Type.TEXT -> txt(c, wd.text.ifBlank { ctx.getString(R.string.dw_text_sample) }, x, y + 12f * s, 34f * s, white, medium)
        }
    }

    fun hit(xPx: Float, yPx: Float, pad: Float): DashWidget? =
        bounds.lastOrNull { (_, r) -> RectF(r).apply { inset(-pad, -pad) }.contains(xPx, yPx) }?.first

    fun boundsOf(wd: DashWidget): RectF? = bounds.firstOrNull { it.first === wd }?.second
}
