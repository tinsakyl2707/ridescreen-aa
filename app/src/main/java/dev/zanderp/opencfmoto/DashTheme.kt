// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.res.ResourcesCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * MotoScreen v18/v19 · a custom dash layout: background + freely placed widgets.
 * Positions are normalised (0..1 of the dash canvas, widget centre) so one theme fits any panel.
 */
data class DashWidget(
    val type: Type, var x: Float, var y: Float, var size: Int = 1, var text: String = "",
    /** Look variant, see [Type.styles]. */
    var style: Int = 0,
    /** 0 = follow the theme colours. */
    var color: Long = 0L,
    /** PHOTO: file name in filesDir. */
    var img: String = "",
) {
    enum class Type(val label: Int, val group: Int, val styles: IntArray = IntArray(0)) {
        GAUGE(R.string.dw_gauge, 0, intArrayOf(R.string.ds_arc, R.string.ds_ring, R.string.ds_bar, R.string.ds_needle, R.string.ds_digital)),
        SPEED(R.string.dw_speed, 0, intArrayOf(R.string.ds_plain, R.string.ds_digital)),
        MAX(R.string.dw_max, 0),
        AVG(R.string.dw_avg, 0),
        TRIP(R.string.dw_trip, 1),
        RIDE_TIME(R.string.dw_ride_time, 1),
        ODO(R.string.dw_odo, 1),
        HEADING(R.string.dw_heading, 1, intArrayOf(R.string.ds_text, R.string.ds_tape)),
        ALTITUDE(R.string.dw_altitude, 1),
        GPS(R.string.dw_gps, 1),
        CLOCK(R.string.dw_clock, 2, intArrayOf(R.string.ds_plain, R.string.ds_seconds, R.string.ds_analog)),
        DATE(R.string.dw_date, 2),
        TEMP(R.string.dw_temp, 2),
        SUN(R.string.dw_sun, 2),
        RAIN(R.string.dw_rain, 2),
        FUEL(R.string.dw_fuel, 2),
        FUEL_COST(R.string.dw_fuel_cost, 2),
        MAINT(R.string.dw_maint, 2),
        BATTERY(R.string.dw_battery, 2),
        TEXT(R.string.dw_text, 3),
        PHOTO(R.string.dw_photo, 3, intArrayOf(R.string.ds_circle, R.string.ds_rounded)),
        PANEL(R.string.dw_panel, 3, intArrayOf(R.string.ds_glass, R.string.ds_outline, R.string.ds_stripe)),
        LINE(R.string.dw_line, 3, intArrayOf(R.string.ds_plain, R.string.ds_fade)),
    }

    fun json() = JSONObject().put("t", type.name).put("x", x.toDouble()).put("y", y.toDouble()).put("s", size)
        .put("txt", text).put("st", style).put("c", color).put("img", img)

    companion object {
        fun from(o: JSONObject): DashWidget? = runCatching {
            DashWidget(
                Type.valueOf(o.getString("t")), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optInt("s", 1),
                o.optString("txt"), o.optInt("st", 0), o.optLong("c", 0L), o.optString("img"),
            )
        }.getOrNull()
    }
}

/** Number font + label font (labels need Thai, so number-only faces pair with Chakra Petch). */
enum class DashFont(val label: Int, val num: Int, val text: Int) {
    KANIT(R.string.df_kanit, R.font.archivo_black, R.font.archivo_semibold),
    CHAKRA(R.string.df_chakra, R.font.ms_chakra_bold, R.font.ms_chakra_medium),
    ORBITRON(R.string.df_orbitron, R.font.ms_orbitron, R.font.ms_chakra_medium),
    DIGITAL(R.string.df_digital, R.font.ms_dseg7, R.font.ms_chakra_medium),
    MONO(R.string.df_mono, R.font.ms_techmono, R.font.ms_chakra_medium),
    PLEX(R.string.df_plex, R.font.archivo_medium, R.font.archivo_regular),
}

data class DashTheme(
    var name: String,
    /** See [BACKGROUNDS]; "art" = MotoScreen rider art, "photo" = rider's own picture. */
    var bg: String = "glow",
    var dim: Float = 0.45f,
    val widgets: MutableList<DashWidget> = mutableListOf(),
    /** Highlight colour; 0 = the app's accent. */
    var accent: Long = 0L,
    /** Main text colour; 0 = white. */
    var ink: Long = 0L,
    var font: Int = 0,
    /** km/h above which speed turns red and the screen edge pulses; 0 = off. */
    var warn: Int = 0,
    /** Gauge full scale, km/h. */
    var max: Int = 140,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("bg", bg).put("dim", dim.toDouble())
        .put("accent", accent).put("ink", ink).put("font", font).put("warn", warn).put("max", max)
        .put("w", JSONArray().apply { widgets.forEach { put(it.json()) } })

    fun copyDeep() = copy(widgets = widgets.map { it.copy() }.toMutableList())

    companion object {
        val BACKGROUNDS = listOf("glow", "sunset", "ocean", "racing", "forest", "carbon", "grid", "black", "art", "photo")
        val COLORS = longArrayOf(
            0xFF22E3D0, 0xFF1AA3FF, 0xFF7C5CFF, 0xFFFF4FA3, 0xFFFF3B30, 0xFFFF8A00,
            0xFFFFC547, 0xFFB6F23A, 0xFF2EE66B, 0xFFEAF4F7, 0xFF9FB3BC, 0xFFFF6B5E,
        )

        fun from(o: JSONObject): DashTheme {
            val list = mutableListOf<DashWidget>()
            o.optJSONArray("w")?.let { a -> for (i in 0 until a.length()) DashWidget.from(a.getJSONObject(i))?.let { list += it } }
            return DashTheme(
                o.optString("name", "MotoScreen"), o.optString("bg", "glow"), o.optDouble("dim", 0.45).toFloat(), list,
                o.optLong("accent", 0L), o.optLong("ink", 0L), o.optInt("font", 0), o.optInt("warn", 0), o.optInt("max", 140),
            )
        }

        private fun w(t: DashWidget.Type, x: Float, y: Float, s: Int = 1, st: Int = 0, txt: String = "", c: Long = 0L) =
            DashWidget(t, x, y, s, txt, st, c)

        /** The Cockpit layout, as widgets — the starting point and "reset". */
        fun cockpit(ctx: Context) = DashTheme(ctx.getString(R.string.dt_default_name), "glow", 0.45f, mutableListOf(
            w(DashWidget.Type.GAUGE, 0.5f, 0.62f),
            w(DashWidget.Type.TRIP, 0.11f, 0.22f),
            w(DashWidget.Type.CLOCK, 0.89f, 0.17f),
            w(DashWidget.Type.DATE, 0.89f, 0.29f),
            w(DashWidget.Type.RAIN, 0.89f, 0.40f),
            w(DashWidget.Type.FUEL, 0.89f, 0.52f),
            w(DashWidget.Type.MAINT, 0.22f, 0.93f, 0),
            w(DashWidget.Type.BATTERY, 0.92f, 0.93f, 0),
        ))

        /** Ready-made looks. Each is an ordinary theme: pick one, then move anything. */
        fun presets(ctx: Context): List<DashTheme> = listOf(
            cockpit(ctx),
            DashTheme(ctx.getString(R.string.dp_sport), "racing", 0.4f, mutableListOf(
                w(DashWidget.Type.PANEL, 0.79f, 0.50f, 2, 1),
                w(DashWidget.Type.GAUGE, 0.33f, 0.55f, 2, 1),
                w(DashWidget.Type.MAX, 0.71f, 0.33f),
                w(DashWidget.Type.AVG, 0.89f, 0.33f),
                w(DashWidget.Type.RIDE_TIME, 0.71f, 0.67f),
                w(DashWidget.Type.TRIP, 0.89f, 0.67f),
                w(DashWidget.Type.CLOCK, 0.92f, 0.10f, 0),
                w(DashWidget.Type.GPS, 0.06f, 0.08f, 0),
            ), accent = 0xFFFF3B30, font = DashFont.ORBITRON.ordinal, warn = 120, max = 180),
            DashTheme(ctx.getString(R.string.dp_touring), "ocean", 0.4f, mutableListOf(
                w(DashWidget.Type.HEADING, 0.5f, 0.09f, 1, 1),
                w(DashWidget.Type.GAUGE, 0.5f, 0.62f, 1, 3),
                w(DashWidget.Type.TRIP, 0.12f, 0.30f),
                w(DashWidget.Type.ODO, 0.12f, 0.56f, 0),
                w(DashWidget.Type.ALTITUDE, 0.12f, 0.74f, 0),
                w(DashWidget.Type.CLOCK, 0.88f, 0.28f),
                w(DashWidget.Type.TEMP, 0.88f, 0.48f),
                w(DashWidget.Type.SUN, 0.88f, 0.62f, 0),
                w(DashWidget.Type.RAIN, 0.88f, 0.76f, 0),
                w(DashWidget.Type.FUEL, 0.25f, 0.93f, 0),
                w(DashWidget.Type.MAINT, 0.75f, 0.93f, 0),
            ), font = DashFont.CHAKRA.ordinal, max = 160),
            DashTheme(ctx.getString(R.string.dp_minimal), "black", 0.4f, mutableListOf(
                w(DashWidget.Type.SPEED, 0.5f, 0.48f, 2),
                w(DashWidget.Type.LINE, 0.5f, 0.80f, 1, 1),
                w(DashWidget.Type.CLOCK, 0.30f, 0.90f, 0),
                w(DashWidget.Type.TRIP, 0.70f, 0.88f, 0),
            ), accent = 0xFFEAF4F7, font = DashFont.PLEX.ordinal),
            DashTheme(ctx.getString(R.string.dp_retro), "carbon", 0.3f, mutableListOf(
                w(DashWidget.Type.PANEL, 0.5f, 0.50f, 2, 1),
                w(DashWidget.Type.SPEED, 0.5f, 0.48f, 2, 1),
                w(DashWidget.Type.CLOCK, 0.13f, 0.20f, 1),
                w(DashWidget.Type.ODO, 0.13f, 0.80f, 0),
                w(DashWidget.Type.TRIP, 0.87f, 0.22f),
                w(DashWidget.Type.TEMP, 0.87f, 0.78f, 0),
            ), accent = 0xFFFFB000, ink = 0xFFFFC547, font = DashFont.DIGITAL.ordinal),
            DashTheme(ctx.getString(R.string.dp_night), "black", 0.6f, mutableListOf(
                w(DashWidget.Type.GAUGE, 0.5f, 0.62f, 1, 0),
                w(DashWidget.Type.CLOCK, 0.12f, 0.18f, 0),
                w(DashWidget.Type.TRIP, 0.88f, 0.22f, 0),
                w(DashWidget.Type.RAIN, 0.5f, 0.10f, 0),
            ), accent = 0xFFB0352A, ink = 0xFFE07B70, font = DashFont.CHAKRA.ordinal),
            DashTheme(ctx.getString(R.string.dp_hero), "art", 0.35f, mutableListOf(
                w(DashWidget.Type.PANEL, 0.17f, 0.72f, 1, 0),
                w(DashWidget.Type.GAUGE, 0.17f, 0.72f, 0, 1),
                w(DashWidget.Type.TEXT, 0.80f, 0.12f, 1, 0, "MotoScreen"),
                w(DashWidget.Type.CLOCK, 0.90f, 0.82f),
                w(DashWidget.Type.TEMP, 0.90f, 0.94f, 0),
            ), font = DashFont.KANIT.ordinal),
        )

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
        fun imageFile(ctx: Context, name: String) = File(ctx.filesDir, name)
    }
}

/** Air temperature now (Open-Meteo `current=temperature_2m`), refreshed at most every 30 min while a TEMP widget is shown. */
object WeatherNow {
    @Volatile var tempC: Double? = null
    @Volatile private var lastTry = 0L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "weather-now").apply { isDaemon = true } }

    fun poke(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTry < (if (tempC == null) 5 * 60_000L else 30 * 60_000L)) return
        lastTry = now
        val app = ctx.applicationContext
        io.execute {
            try {
                val loc = RideExtras.lastLocation(app, 3 * 3_600_000L) ?: run { lastTry = now - 25 * 60_000L; return@execute }
                val lat = String.format(Locale.ROOT, "%.2f", loc.latitude)
                val lon = String.format(Locale.ROOT, "%.2f", loc.longitude)
                val body = AppHttp.getText("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m")
                val t = JSONObject(body).optJSONObject("current")?.optDouble("temperature_2m")
                if (t != null && !t.isNaN()) tempC = t
                LogBus.log("[DASH] temperature now: $t °C")
            } catch (e: Exception) {
                LogBus.log("[DASH] temperature failed: ${e.message}")
            }
        }
    }
}

/** Sunrise / sunset from position and date (standard sunrise equation, no network). */
object SunTimes {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** (sunrise, sunset) in epoch ms for the day containing [dayMs], or null in polar day/night. */
    fun of(lat: Double, lon: Double, dayMs: Long): Pair<Long, Long>? {
        val jd = dayMs / 86_400_000.0 + 2440587.5
        val n = floor(jd - 2451545.0 + 0.0008)
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360.0
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180.0 + 102.9372) % 360.0
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val decl = asin(sin(rad(lambda)) * sin(rad(23.4397)))
        val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sin(decl)) / (cos(rad(lat)) * cos(decl))
        if (cosW < -1 || cosW > 1) return null
        val w0 = deg(acos(cosW))
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).toLong()
        return ms(jTransit - w0 / 360.0) to ms(jTransit + w0 / 360.0)
    }

    /** The next sunrise or sunset after now: (isSunset, time). */
    fun next(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Pair<Boolean, Long>? {
        val today = of(lat, lon, now) ?: return null
        if (now < today.first) return false to today.first
        if (now < today.second) return true to today.second
        val tomorrow = of(lat, lon, now + 86_400_000L) ?: return null
        return false to tomorrow.first
    }
}

/**
 * Draws a [DashTheme] — shared by the dash itself ([CockpitView]) and the editor preview, so what
 * the rider arranges is exactly what the bike shows. [sample] = editor without live data: show
 * believable placeholder values so every widget is visible.
 */
class DashRenderer(private val ctx: Context) {
    data class Live(
        val speedKmh: Int, val hasFix: Boolean,
        val bearing: Float? = null, val altitudeM: Double? = null, val accuracyM: Float? = null,
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fonts = HashMap<Int, Typeface>()
    private fun font(id: Int) = fonts.getOrPut(id) { try { ResourcesCompat.getFont(ctx, id) ?: Typeface.DEFAULT_BOLD } catch (_: Exception) { Typeface.DEFAULT_BOLD } }
    private val bodyTf get() = font(R.font.archivo_regular)

    private val muted = Color.parseColor("#9FB3BC")
    private val track = Color.parseColor("#33FFFFFF")
    private val amber = Color.parseColor("#FFC547")
    private val red = Color.parseColor("#FF4A3D")

    // per-draw theme state
    private var accent = 0
    private var ink = Color.WHITE
    private var numTf: Typeface = Typeface.DEFAULT_BOLD
    private var textTf: Typeface = Typeface.DEFAULT
    private var digitalFont = false
    private var overWarn = false

    private var bgKey: String? = null
    private var bgBitmap: Bitmap? = null
    private val images = HashMap<String, Bitmap?>()

    // slow data, refreshed every 15 s
    private var slowAt = 0L
    private var maint: Maintenance.Status? = null
    private var odo: Double? = null
    private var lPer100: Double? = null
    private var sun: Pair<Boolean, Long>? = null

    /** Bounds of each widget from the last draw, in draw order, for the editor's hit-testing. */
    val bounds = ArrayList<Pair<DashWidget, RectF>>()

    private fun refreshSlow() {
        val now = System.currentTimeMillis()
        if (now - slowAt < 15_000) return
        slowAt = now
        try {
            val b = Maintenance.load(ctx)
            odo = b.odometer
            lPer100 = Maintenance.consumption(b)?.first
            maint = Maintenance.mostUrgent(ctx)
        } catch (_: Exception) {}
        sun = try { RideExtras.lastLocation(ctx, 24 * 3_600_000L)?.let { SunTimes.next(it.latitude, it.longitude) } } catch (_: Exception) { null }
    }

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

    private fun gradientBg(c: Canvas, w: Float, h: Float, theme: DashTheme) {
        p.style = Paint.Style.FILL
        fun radial(inner: String, outer: String, cy: Float = 1.2f) {
            p.shader = RadialGradient(w / 2, h * cy, h * 1.15f, Color.parseColor(inner), Color.parseColor(outer), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
        }
        when (theme.bg) {
            "black" -> { p.color = Color.BLACK; c.drawRect(0f, 0f, w, h, p) }
            "sunset" -> {
                p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.parseColor("#1B0B2E"), Color.parseColor("#5A1A4A"), Color.parseColor("#C2462E")), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
            "ocean" -> radial("#0B3A66", "#03101F")
            "racing" -> {
                radial("#3A0A0A", "#090303")
                p.color = Color.argb(40, 255, 255, 255); p.strokeWidth = h * 0.012f
                for (i in 0..8) { val x = w * (0.55f + i * 0.06f); c.drawLine(x, h, x + h * 0.5f, 0f, p) }
            }
            "forest" -> radial("#0E3B1E", "#040B06")
            "carbon" -> {
                p.color = Color.parseColor("#0B0C0E"); c.drawRect(0f, 0f, w, h, p)
                val cell = h / 28f
                var y = 0f; var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) 0f else cell
                    while (x < w) {
                        p.color = Color.parseColor("#17191C"); c.drawRect(x, y, x + cell, y + cell / 2, p)
                        p.color = Color.parseColor("#121315"); c.drawRect(x, y + cell / 2, x + cell, y + cell, p)
                        x += cell * 2
                    }
                    y += cell; row++
                }
                p.shader = RadialGradient(w / 2, h / 2, w * 0.7f, Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
            "grid" -> {
                p.color = Color.parseColor("#050814"); c.drawRect(0f, 0f, w, h, p)
                p.color = (accent and 0x00FFFFFF) or (0x30 shl 24); p.strokeWidth = 1.2f
                val step = h / 10f
                var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
                var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, p); y += step }
                p.shader = RadialGradient(w / 2, h * 1.1f, h, (accent and 0x00FFFFFF) or (0x40 shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
            else -> radial("#0D3B3D", "#050709")
        }
    }

    fun draw(c: Canvas, w: Float, h: Float, theme: DashTheme, live: Live?, sample: Boolean) {
        val k = w / 1024f
        accent = if (theme.accent != 0L) theme.accent.toInt() else MotoScreen.accent(ctx).swatch.toInt()
        ink = if (theme.ink != 0L) theme.ink.toInt() else Color.parseColor("#EAF4F7")
        val f = DashFont.values().getOrElse(theme.font) { DashFont.KANIT }
        numTf = font(f.num); textTf = font(f.text); digitalFont = f == DashFont.DIGITAL
        refreshSlow()
        val speed = live?.takeIf { it.hasFix }?.speedKmh ?: if (sample) 68 else -1
        overWarn = theme.warn > 0 && speed > theme.warn

        p.shader = null; p.style = Paint.Style.FILL
        val bmp = background(theme)
        if (bmp != null) {
            val s = maxOf(w / bmp.width, h / bmp.height)
            val sw = (w / s).toInt(); val sh = (h / s).toInt()
            val sx = (bmp.width - sw) / 2; val sy = (bmp.height - sh) / 2
            c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), p)
            p.color = Color.argb((theme.dim.coerceIn(0f, 0.9f) * 255).toInt(), 0, 0, 0)
            c.drawRect(0f, 0f, w, h, p)
        } else {
            gradientBg(c, w, h, theme)
            if (theme.dim > 0.46f) { p.color = Color.argb(((theme.dim - 0.45f) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, p) }
        }
        bounds.clear()
        for (wd in theme.widgets) bounds += wd to drawWidget(c, wd, w, h, k, live, sample, speed, theme)

        if (overWarn) {   // pulsing red edge
            val a = (110 + 110 * sin(System.currentTimeMillis() / 160.0)).toInt().coerceIn(0, 255)
            p.style = Paint.Style.STROKE; p.strokeWidth = 14f * k; p.color = Color.argb(a, 255, 60, 50)
            c.drawRect(7f * k, 7f * k, w - 7f * k, h - 7f * k, p); p.style = Paint.Style.FILL
        }
    }

    private fun scale(size: Int) = when (size) { 0 -> 0.75f; 2 -> 1.4f; else -> 1f }

    /** [numeric]: use the theme's number face (DSEG only has digits and a few symbols). */
    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, numeric: Boolean, align: Paint.Align = Paint.Align.CENTER): RectF {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; p.textSize = size
        p.typeface = if (numeric) numTf else textTf
        p.textAlign = align
        p.setShadowLayer(size * 0.12f, 0f, 0f, Color.argb(170, 0, 0, 0))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
        val tw = p.measureText(s)
        val l = when (align) { Paint.Align.LEFT -> x; Paint.Align.RIGHT -> x - tw; else -> x - tw / 2 }
        return RectF(l, y - size * 0.85f, l + tw, y + size * 0.2f)
    }

    /** A 7-segment readout with the unlit "8"s faintly behind, like a real LCD. */
    private fun lcd(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int): RectF {
        p.shader = null; p.style = Paint.Style.FILL; p.textSize = size; p.typeface = font(R.font.ms_dseg7); p.textAlign = Paint.Align.CENTER
        val ghost = s.map { if (it.isDigit() || it == '-' || it == '!') '8' else it }.joinToString("")
        p.color = (color and 0x00FFFFFF) or (0x22 shl 24)
        c.drawText(ghost, x, y, p)
        p.color = color
        p.setShadowLayer(size * 0.18f, 0f, 0f, (color and 0x00FFFFFF) or (0x99 shl 24))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
        val tw = p.measureText(ghost)
        return RectF(x - tw / 2, y - size, x + tw / 2, y + size * 0.1f)
    }

    private fun chip(c: Canvas, s: String, x: Float, y: Float, size: Float, fg: Int, bg: Int): RectF {
        p.typeface = textTf; p.textSize = size
        val tw = p.measureText(s) + size * 1.4f; val th = size * 2f
        val r = RectF(x - tw / 2, y - th / 2, x + tw / 2, y + th / 2)
        p.shader = null; p.style = Paint.Style.FILL; p.color = bg
        c.drawRoundRect(r, th * 0.35f, th * 0.35f, p)
        p.color = fg; p.textAlign = Paint.Align.CENTER
        c.drawText(s, x, y + size * 0.36f, p)
        return r
    }

    /** Small label over a big value — the standard "data field". */
    private fun field(c: Canvas, label: String, value: String, x: Float, y: Float, s: Float, hi: Int, valueColor: Int, unit: String = ""): RectF {
        val a = txt(c, label, x, y - 24f * s, 17f * s, hi, false)
        val b = if (digitalFont) lcd(c, value, x, y + 24f * s, 38f * s, valueColor) else txt(c, value, x, y + 22f * s, 46f * s, valueColor, true)
        val r = RectF(a).apply { union(b) }
        if (unit.isNotEmpty()) r.union(txt(c, unit, x, y + 46f * s, 15f * s, muted, false))
        return r
    }

    private fun empty(x: Float, y: Float, s: Float) = RectF(x - 50 * s, y - 16 * s, x + 50 * s, y + 16 * s)

    private fun drawWidget(c: Canvas, wd: DashWidget, w: Float, h: Float, k: Float, live: Live?, sample: Boolean, speed: Int, theme: DashTheme): RectF {
        val s = scale(wd.size) * k
        val x = wd.x * w; val y = wd.y * h
        val hi = if (wd.color != 0L) wd.color.toInt() else accent
        val main = if (wd.color != 0L) wd.color.toInt() else ink
        val speedCol = if (overWarn) red else main
        val trip = TripLogger.current?.takeIf { it.recording }?.snapshot()
        val loc = Locale.getDefault()
        val th = loc.language == "th"
        val dash = "--"
        return when (wd.type) {
            DashWidget.Type.GAUGE -> gauge(c, wd.style, x, y, s, speed, theme.max, hi, speedCol)
            DashWidget.Type.SPEED -> {
                val v = if (speed < 0) dash else speed.toString()
                val a = if (wd.style == 1 || digitalFont) lcd(c, v, x, y + 50f * s, 150f * s, speedCol) else txt(c, v, x, y + 50f * s, 170f * s, speedCol, true)
                RectF(a).apply { union(txt(c, ctx.getString(R.string.dash_kmh), x, y + 84f * s, 24f * s, hi, false)) }
            }
            DashWidget.Type.MAX -> {
                val v = trip?.let { (it.maxSpeedMs * 3.6f).toInt() } ?: if (sample) 112 else null
                field(c, ctx.getString(R.string.dw_max), v?.toString() ?: dash, x, y, s, hi, main)
            }
            DashWidget.Type.AVG -> {
                val avg = trip?.let { t -> if (t.movingTimeMs > 0) (t.distanceMeters / 1000.0) / (t.movingTimeMs / 3_600_000.0) else 0.0 } ?: if (sample) 38.0 else null
                field(c, ctx.getString(R.string.dw_avg), avg?.let { String.format(Locale.US, "%.0f", it) } ?: dash, x, y, s, hi, main)
            }
            DashWidget.Type.TRIP -> {
                val km = (trip?.distanceMeters ?: if (sample) 12400.0 else 0.0) / 1000.0
                field(c, ctx.getString(R.string.dash_trip), String.format(Locale.US, "%.1f", km), x, y, s, hi, main, ctx.getString(R.string.dash_km))
            }
            DashWidget.Type.RIDE_TIME -> {
                val ms = trip?.movingTimeMs ?: if (sample) 21 * 60_000L else 0L
                field(c, ctx.getString(R.string.dw_ride_time), String.format(Locale.US, "%d:%02d", ms / 3_600_000, (ms / 60_000) % 60), x, y, s, hi, main)
            }
            DashWidget.Type.ODO -> {
                val v = odo ?: if (sample) 12345.0 else null
                field(c, "ODO", v?.let { String.format(Locale.US, if (digitalFont) "%.0f" else "%,.0f", it) } ?: dash, x, y, s, hi, main, ctx.getString(R.string.dash_km))
            }
            DashWidget.Type.HEADING -> {
                val b = live?.bearing ?: if (sample) 48f else null
                if (wd.style == 1) tape(c, x, y, s, b, hi, main)
                else field(c, ctx.getString(R.string.dw_heading), b?.let { compass(it) + " " + it.toInt() + "°" } ?: dash, x, y, s, hi, main)
            }
            DashWidget.Type.ALTITUDE -> {
                val a = live?.altitudeM ?: if (sample) 182.0 else null
                field(c, ctx.getString(R.string.dw_altitude), a?.let { String.format(Locale.US, "%.0f", it) } ?: dash, x, y, s, hi, main, if (th) "ม." else "m")
            }
            DashWidget.Type.GPS -> gpsBars(c, x, y, s, live?.takeIf { it.hasFix }?.accuracyM ?: if (sample) 4f else null, hi)
            DashWidget.Type.CLOCK -> {
                val now = Date()
                when (wd.style) {
                    2 -> analogClock(c, x, y, s, hi, main)
                    1 -> if (digitalFont) lcd(c, SimpleDateFormat("HH:mm:ss", loc).format(now), x, y + 20f * s, 44f * s, main)
                         else txt(c, SimpleDateFormat("HH:mm:ss", loc).format(now), x, y + 20f * s, 50f * s, main, true)
                    else -> if (digitalFont) lcd(c, SimpleDateFormat("HH:mm", loc).format(now), x, y + 20f * s, 48f * s, main)
                         else txt(c, SimpleDateFormat("HH:mm", loc).format(now), x, y + 20f * s, 54f * s, main, true)
                }
            }
            DashWidget.Type.DATE -> txt(c, SimpleDateFormat("EEE d MMM", loc).format(Date()), x, y + 7f * s, 20f * s, muted, false)
            DashWidget.Type.TEMP -> {
                WeatherNow.poke(ctx)
                val t = WeatherNow.tempC ?: if (sample) 31.0 else null
                if (digitalFont) lcd(c, t?.let { String.format(Locale.US, "%.0f", it) } ?: dash, x, y + 18f * s, 40f * s, main).apply { union(txt(c, "°C", right + 14 * s, y + 14f * s, 18f * s, hi, false)) }
                else txt(c, "🌡 " + (t?.let { String.format(Locale.US, "%.0f°C", it) } ?: "--°C"), x, y + 12f * s, 32f * s, main, false)
            }
            DashWidget.Type.SUN -> {
                val n = sun ?: if (sample) (true to System.currentTimeMillis() + 2 * 3_600_000L) else null
                if (n == null) empty(x, y, s)
                else txt(c, (if (n.first) "🌇 " else "🌅 ") + SimpleDateFormat("HH:mm", loc).format(Date(n.second)), x, y + 8f * s, 24f * s, main, false)
            }
            DashWidget.Type.FUEL -> {
                val snap = FuelPrice.cached(ctx)
                val fu = FuelPrice.primary(ctx)
                val pr = snap?.prices?.get(fu)
                val t = pr?.today
                val label = when {
                    t != null -> {
                        val d = pr?.tomorrow?.let { it - t } ?: 0.0
                        val tr = when { d > 0.004 -> " ▲" + String.format(Locale.US, "%.2f", d); d < -0.004 -> " ▼" + String.format(Locale.US, "%.2f", -d); else -> "" }
                        "⛽ " + ctx.getString(fu.label) + " " + String.format(Locale.US, "%.2f", t) + " ฿" + tr
                    }
                    sample -> "⛽ E20 33.50 ฿"
                    else -> null
                }
                if (label == null) empty(x, y, s) else chip(c, label, x, y, 20f * s, ink, Color.parseColor("#B3101820"))
            }
            DashWidget.Type.FUEL_COST -> {
                val price = FuelPrice.cached(ctx)?.prices?.get(FuelPrice.primary(ctx))?.today
                val km = (trip?.distanceMeters ?: 0.0) / 1000.0
                val cost = if (price != null && lPer100 != null) km * lPer100!! / 100.0 * price else null
                field(c, ctx.getString(R.string.dw_fuel_cost), cost?.let { String.format(Locale.US, "%.0f", it) } ?: if (sample) "18" else dash, x, y, s, hi, main, "฿")
            }
            DashWidget.Type.RAIN -> {
                val alert = RainCheck.lastAlert?.takeIf { System.currentTimeMillis() - it.second < 90 * 60_000L }?.first
                val label = alert ?: if (sample) ctx.getString(R.string.dw_rain_sample) else null
                if (label == null) empty(x, y, s) else chip(c, "🌧 $label", x, y, 20f * s, amber, Color.parseColor("#B3332A10"))
            }
            DashWidget.Type.MAINT -> {
                val st = maint
                val left = st?.kmLeft
                val label = when {
                    st != null && left != null && left <= 0 -> ctx.getString(R.string.dash_maint_over, st.task.name)
                    st != null && left != null -> ctx.getString(R.string.dash_maint_left, st.task.name, String.format(Locale.US, "%,.0f", left))
                    sample -> ctx.getString(R.string.dash_maint_left, ctx.getString(R.string.maint_oil_engine), "2,450")
                    else -> ""
                }
                if (label.isEmpty()) empty(x, y, s)
                else txt(c, "🔧 $label", x, y + 7f * s, 20f * s, if (left != null && left <= 0) red else muted, false)
            }
            DashWidget.Type.BATTERY -> {
                val b = try { ctx.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { null }
                txt(c, "📱 ${b ?: 82}%", x, y + 7f * s, 20f * s, if ((b ?: 100) < 20) red else muted, false)
            }
            DashWidget.Type.TEXT -> txt(c, wd.text.ifBlank { ctx.getString(R.string.dw_text_sample) }, x, y + 12f * s, 34f * s, main, false)
            DashWidget.Type.PHOTO -> photo(c, wd, x, y, s, hi)
            DashWidget.Type.PANEL -> panel(c, wd.style, x, y, s, hi)
            DashWidget.Type.LINE -> {
                val half = 200f * s; val t = 3f * s
                p.style = Paint.Style.FILL
                p.shader = if (wd.style == 1) LinearGradient(x - half, 0f, x + half, 0f, intArrayOf(Color.TRANSPARENT, hi, Color.TRANSPARENT), null, Shader.TileMode.CLAMP) else null
                p.color = hi
                c.drawRect(x - half, y - t / 2, x + half, y + t / 2, p); p.shader = null
                RectF(x - half, y - 12 * s, x + half, y + 12 * s)
            }
        }
    }

    // ---- gauges ----

    private fun gauge(c: Canvas, style: Int, x: Float, y: Float, s: Float, speed: Int, max: Int, hi: Int, col: Int): RectF {
        val frac = (speed.coerceAtLeast(0) / max.toFloat()).coerceIn(0f, 1f)
        val num = if (speed < 0) "--" else speed.toString()
        val unit = ctx.getString(if (speed < 0) R.string.dash_gps_wait else R.string.dash_kmh)
        val arcCol = if (overWarn) red else hi
        val second = Color.parseColor("#1AA3FF")
        p.shader = null
        return when (style) {
            1 -> {  // full ring, number inside
                val r = 120f * s
                val box = RectF(x - r, y - r, x + r, y + r)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 18f * s; p.color = track
                c.drawArc(box, 120f, 300f, false, p)
                if (frac > 0) { p.color = arcCol; p.setShadowLayer(12f * s, 0f, 0f, arcCol); c.drawArc(box, 120f, 300f * frac, false, p); p.clearShadowLayer() }
                p.style = Paint.Style.FILL
                if (digitalFont) lcd(c, num, x, y + 28f * s, 80f * s, col) else txt(c, num, x, y + 34f * s, 100f * s, col, true)
                txt(c, unit, x, y + 72f * s, 20f * s, muted, false)
                RectF(box).apply { inset(-12 * s, -12 * s) }
            }
            2 -> {  // segmented bar
                val wBar = 420f * s; val segs = 24; val gap = 4f * s; val segW = (wBar - gap * (segs - 1)) / segs
                val top = y + 30f * s; val hBar = 26f * s
                val lit = (frac * segs).toInt()
                for (i in 0 until segs) {
                    val l = x - wBar / 2 + i * (segW + gap)
                    p.style = Paint.Style.FILL
                    p.color = when {
                        i >= lit -> track
                        i > segs * 0.85f || overWarn -> red
                        i > segs * 0.65f -> amber
                        else -> arcCol
                    }
                    c.drawRoundRect(l, top, l + segW, top + hBar, 3f * s, 3f * s, p)
                }
                val a = if (digitalFont) lcd(c, num, x - 40f * s, y + 6f * s, 80f * s, col) else txt(c, num, x - 40f * s, y + 10f * s, 100f * s, col, true)
                txt(c, unit, x + wBar / 2 - 40f * s, y + 6f * s, 20f * s, muted, false)
                RectF(x - wBar / 2, a.top, x + wBar / 2, top + hBar + 6 * s)
            }
            3 -> {  // analog needle
                val r = 150f * s
                val start = 150f; val sweep = 240f
                val box = RectF(x - r, y - r, x + r, y + r)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; p.strokeWidth = 6f * s; p.color = track
                c.drawArc(box, start, sweep, false, p)
                p.color = red; p.strokeWidth = 8f * s
                c.drawArc(box, start + sweep * 0.83f, sweep * 0.17f, false, p)
                p.strokeCap = Paint.Cap.ROUND
                val step = if (max > 160) 20 else 10
                var v = 0
                while (v <= max) {
                    val ang = Math.toRadians((start + sweep * v / max).toDouble())
                    val major = v % (step * 2) == 0
                    val r1 = r - (if (major) 22f else 12f) * s
                    p.color = if (major) ink else muted; p.strokeWidth = (if (major) 4f else 2f) * s
                    c.drawLine(x + (r * cos(ang)).toFloat(), y + (r * sin(ang)).toFloat(), x + (r1 * cos(ang)).toFloat(), y + (r1 * sin(ang)).toFloat(), p)
                    if (major) {
                        p.style = Paint.Style.FILL; p.textSize = 17f * s; p.typeface = numTf.takeIf { !digitalFont } ?: textTf; p.textAlign = Paint.Align.CENTER; p.color = muted
                        val rt = r - 42f * s
                        c.drawText(v.toString(), x + (rt * cos(ang)).toFloat(), y + (rt * sin(ang)).toFloat() + 6f * s, p)
                        p.style = Paint.Style.STROKE
                    }
                    v += step
                }
                val ang = Math.toRadians((start + sweep * frac).toDouble())
                p.color = arcCol; p.strokeWidth = 6f * s; p.setShadowLayer(10f * s, 0f, 0f, arcCol)
                c.drawLine(x - (18f * s * cos(ang)).toFloat(), y - (18f * s * sin(ang)).toFloat(), x + ((r - 18f * s) * cos(ang)).toFloat(), y + ((r - 18f * s) * sin(ang)).toFloat(), p)
                p.clearShadowLayer()
                p.style = Paint.Style.FILL; p.color = ink; c.drawCircle(x, y, 10f * s, p)
                if (digitalFont) lcd(c, num, x, y + 78f * s, 44f * s, col) else txt(c, num, x, y + 82f * s, 52f * s, col, true)
                txt(c, unit, x, y + 106f * s, 16f * s, muted, false)
                RectF(box).apply { inset(-8 * s, -8 * s); bottom = y + 112f * s }
            }
            4 -> {  // digital LCD in a bezel
                val bw = 300f * s; val bh = 150f * s
                val r = RectF(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)
                p.style = Paint.Style.FILL; p.color = Color.argb(150, 0, 0, 0); c.drawRoundRect(r, 16f * s, 16f * s, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * s; p.color = (arcCol and 0x00FFFFFF) or (0x88 shl 24); c.drawRoundRect(r, 16f * s, 16f * s, p)
                p.style = Paint.Style.FILL
                lcd(c, if (speed < 0) "---" else String.format(Locale.US, "%3d", speed).replace(' ', '!'), x, y + 34f * s, 96f * s, col)
                txt(c, unit, x + bw / 2 - 14f * s, y + bh / 2 - 12f * s, 16f * s, muted, false, Paint.Align.RIGHT)
                // thin progress line along the bottom
                p.color = track; c.drawRect(r.left + 14 * s, r.bottom - 8 * s, r.right - 14 * s, r.bottom - 5 * s, p)
                p.color = arcCol; c.drawRect(r.left + 14 * s, r.bottom - 8 * s, r.left + 14 * s + (r.width() - 28 * s) * frac, r.bottom - 5 * s, p)
                r
            }
            else -> {  // arc (classic)
                val r = 160f * s
                val arc = RectF(x - r, y - r * 0.55f, x + r, y + r * 1.45f)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 20f * s; p.color = track
                c.drawArc(arc, 160f, 220f, false, p)
                if (frac > 0) {
                    p.shader = if (overWarn) null else LinearGradient(x - r, 0f, x + r, 0f, hi, second, Shader.TileMode.CLAMP)
                    p.color = arcCol
                    c.drawArc(arc, 160f, 220f * frac, false, p); p.shader = null
                }
                p.style = Paint.Style.FILL
                if (digitalFont) lcd(c, num, x, y + 36f * s, 104f * s, col) else txt(c, num, x, y + 40f * s, 130f * s, col, true)
                txt(c, unit, x, y + 78f * s, 22f * s, muted, false)
                RectF(x - r - 10 * s, y - r * 0.55f - 10 * s, x + r + 10 * s, y + 90f * s)
            }
        }
    }

    private fun compass(b: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((b % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]
    }

    /** A sliding compass strip (±60°) with a centre marker. */
    private fun tape(c: Canvas, x: Float, y: Float, s: Float, bearing: Float?, hi: Int, main: Int): RectF {
        val half = 220f * s; val hgt = 46f * s
        val r = RectF(x - half, y - hgt / 2, x + half, y + hgt / 2)
        p.style = Paint.Style.FILL; p.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(Color.TRANSPARENT, Color.argb(140, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(r, p); p.shader = null
        c.save(); c.clipRect(r)
        val b = bearing ?: 0f
        val pxPerDeg = half / 60f
        var d = (floor((b - 70) / 15.0) * 15).toInt()
        while (d <= b + 70) {
            val px = x + (d - b) * pxPerDeg
            val nd = ((d % 360) + 360) % 360
            val card = nd % 45 == 0
            p.color = if (bearing == null) track else if (card) main else muted
            p.strokeWidth = 2f * s
            c.drawLine(px, r.bottom - (if (card) 16f else 9f) * s, px, r.bottom, p)
            if (card && bearing != null) {
                p.style = Paint.Style.FILL; p.textSize = 20f * s; p.typeface = textTf; p.textAlign = Paint.Align.CENTER
                p.color = if (nd == 0) hi else main
                c.drawText(compass(nd.toFloat()), px, r.top + 22f * s, p)
            }
            d += 15
        }
        c.restore()
        p.style = Paint.Style.FILL; p.color = hi
        val tri = Path().apply { moveTo(x - 8 * s, r.bottom + 8 * s); lineTo(x + 8 * s, r.bottom + 8 * s); lineTo(x, r.bottom - 2 * s); close() }
        c.drawPath(tri, p)
        if (bearing == null) txt(c, "--", x, y + 8f * s, 22f * s, muted, false)
        return RectF(r).apply { bottom += 10 * s }
    }

    private fun gpsBars(c: Canvas, x: Float, y: Float, s: Float, acc: Float?, hi: Int): RectF {
        val lit = when { acc == null -> 0; acc <= 5 -> 4; acc <= 10 -> 3; acc <= 25 -> 2; else -> 1 }
        val bw = 7f * s; val gap = 4f * s
        val l = x - 2 * (bw + gap) + 26f * s
        val a = txt(c, "GPS", x - 30f * s, y + 8f * s, 18f * s, if (lit == 0) red else muted, false)
        for (i in 0 until 4) {
            val bh = (8f + i * 6f) * s
            p.style = Paint.Style.FILL; p.color = if (i < lit) hi else track
            c.drawRect(l + i * (bw + gap), y + 10f * s - bh, l + i * (bw + gap) + bw, y + 10f * s, p)
        }
        return RectF(a.left, y - 16f * s, l + 4 * (bw + gap), y + 12f * s)
    }

    private fun analogClock(c: Canvas, x: Float, y: Float, s: Float, hi: Int, main: Int): RectF {
        val r = 54f * s
        val cal = Calendar.getInstance()
        p.style = Paint.Style.FILL; p.color = Color.argb(120, 0, 0, 0); c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * s; p.color = (hi and 0x00FFFFFF) or (0xAA shl 24); c.drawCircle(x, y, r, p)
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            p.color = muted; p.strokeWidth = (if (i % 3 == 0) 3f else 1.5f) * s
            c.drawLine(x + (r * 0.82f * sin(a)).toFloat(), y - (r * 0.82f * cos(a)).toFloat(), x + (r * 0.95f * sin(a)).toFloat(), y - (r * 0.95f * cos(a)).toFloat(), p)
        }
        val min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val hr = (cal.get(Calendar.HOUR) + min / 60f)
        fun hand(angDeg: Double, len: Float, wid: Float, col: Int) {
            val a = Math.toRadians(angDeg)
            p.color = col; p.strokeWidth = wid
            c.drawLine(x, y, x + (len * sin(a)).toFloat(), y - (len * cos(a)).toFloat(), p)
        }
        hand(hr * 30.0, r * 0.5f, 5f * s, main)
        hand(min * 6.0, r * 0.75f, 3.5f * s, main)
        hand(cal.get(Calendar.SECOND) * 6.0, r * 0.82f, 1.5f * s, hi)
        p.style = Paint.Style.FILL; p.color = hi; c.drawCircle(x, y, 4f * s, p)
        return RectF(x - r, y - r, x + r, y + r)
    }

    private fun photo(c: Canvas, wd: DashWidget, x: Float, y: Float, s: Float, hi: Int): RectF {
        val d = 140f * s
        val r = RectF(x - d / 2, y - d / 2, x + d / 2, y + d / 2)
        val bmp = if (wd.img.isEmpty()) null else images.getOrPut(wd.img) {
            runCatching { BitmapFactory.decodeFile(DashTheme.imageFile(ctx, wd.img).absolutePath) }.getOrNull()
        }
        if (bmp == null) {
            p.style = Paint.Style.FILL; p.color = Color.argb(90, 255, 255, 255)
            if (wd.style == 1) c.drawRoundRect(r, 18 * s, 18 * s, p) else c.drawOval(r, p)
            txt(c, "🖼", x, y + 14f * s, 40f * s, ink, false)
        } else {
            val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val sc = maxOf(d / bmp.width, d / bmp.height)
            sh.setLocalMatrix(Matrix().apply { setScale(sc, sc); postTranslate(x - bmp.width * sc / 2, y - bmp.height * sc / 2) })
            p.style = Paint.Style.FILL; p.shader = sh
            if (wd.style == 1) c.drawRoundRect(r, 18 * s, 18 * s, p) else c.drawOval(r, p)
            p.shader = null
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f * s; p.color = hi
        if (wd.style == 1) c.drawRoundRect(r, 18 * s, 18 * s, p) else c.drawOval(r, p)
        p.style = Paint.Style.FILL
        return r
    }

    private fun panel(c: Canvas, style: Int, x: Float, y: Float, s: Float, hi: Int): RectF {
        val pw = 300f * s; val ph = 150f * s
        val r = RectF(x - pw / 2, y - ph / 2, x + pw / 2, y + ph / 2)
        val rad = 18f * s
        when (style) {
            1 -> {
                p.style = Paint.Style.FILL; p.color = Color.argb(70, 0, 0, 0); c.drawRoundRect(r, rad, rad, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * s; p.color = (hi and 0x00FFFFFF) or (0xCC shl 24); c.drawRoundRect(r, rad, rad, p)
            }
            2 -> {
                p.style = Paint.Style.FILL; p.color = Color.argb(140, 0, 0, 0); c.drawRoundRect(r, rad, rad, p)
                p.color = hi; c.drawRoundRect(RectF(r.left, r.top, r.left + 8f * s, r.bottom), 4f * s, 4f * s, p)
            }
            else -> {
                p.style = Paint.Style.FILL; p.color = Color.argb(120, 10, 16, 22); c.drawRoundRect(r, rad, rad, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * s; p.color = Color.argb(50, 255, 255, 255); c.drawRoundRect(r, rad, rad, p)
            }
        }
        p.style = Paint.Style.FILL
        return r
    }

    fun hit(xPx: Float, yPx: Float, pad: Float): DashWidget? {
        // Prefer content over decor: a panel is only picked when nothing on top of it is hit.
        val hits = bounds.filter { (_, r) -> RectF(r).apply { inset(-pad, -pad) }.contains(xPx, yPx) }.map { it.first }
        return hits.lastOrNull { it.type != DashWidget.Type.PANEL && it.type != DashWidget.Type.LINE } ?: hits.lastOrNull()
    }

    fun boundsOf(wd: DashWidget): RectF? = bounds.firstOrNull { it.first === wd }?.second

    /** Small preview picture of a theme (gallery thumbnails). */
    fun thumbnail(theme: DashTheme, widthPx: Int): Bitmap {
        val hPx = (widthPx * 425f / 1024f).toInt()
        val bmp = Bitmap.createBitmap(widthPx, hPx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), widthPx.toFloat(), hPx.toFloat(), theme, null, sample = true)
        return bmp
    }
}

/** Live dash data from a GPS fix. */
internal fun Location.toLive(fresh: Boolean) = DashRenderer.Live(
    speedKmh = if (hasSpeed()) (speed * 3.6f).toInt() else 0,
    hasFix = fresh,
    bearing = if (hasBearing() && hasSpeed() && speed > 1.5f) bearing else null,
    altitudeM = if (hasAltitude()) altitude else null,
    accuracyM = if (hasAccuracy()) accuracy else null,
)
