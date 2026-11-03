// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 🎨 MotoScreen v18 · drag-and-drop dash editor. Landscape: live preview (same [DashRenderer] the
 * bike uses, with sample values) on the left, tools on the right. Drag a widget to move it (snaps to
 * a grid and the centre lines), tap to select, long-press for options. Save = the dash updates at once
 * if it's on (the cockpit reads [DashTheme.current] every frame).
 */
class DashEditorActivity : AppCompatActivity() {
    private lateinit var dash: DashTheme
    private val undo = ArrayDeque<DashTheme>()
    private var dirty = false
    private lateinit var preview: PreviewView
    private lateinit var selLabel: TextView

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importPhoto(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dash = DashTheme.current(this).copyDeep()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val c = { id: Int -> ContextCompat.getColor(this, id) }
        val heavy = ResourcesCompat.getFont(this, R.font.archivo_black)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c(R.color.bg))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + px(12), b.top + px(10), b.right + px(12), b.bottom + px(10)); insets
        }

        // ---- left: title + preview ----
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        left.addView(TextView(this).apply {
            setText(R.string.de_title); textSize = 20f; typeface = heavy; setTextColor(c(R.color.text_primary))
        })
        selLabel = TextView(this).apply {
            setText(R.string.de_hint); textSize = 13f; setTextColor(c(R.color.text_secondary)); setPadding(0, px(2), 0, px(8))
        }
        left.addView(selLabel)
        preview = PreviewView(this)
        left.addView(FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#22000000"))
            addView(preview, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        left.addView(TextView(this).apply {
            setText(R.string.de_note); textSize = 11f; setTextColor(c(R.color.text_secondary)); setPadding(0, px(6), 0, 0)
        })
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        // ---- right: tools ----
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
        fun tool(label: Int, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
            setText(label); textSize = 14f; gravity = Gravity.CENTER
            typeface = ResourcesCompat.getFont(this@DashEditorActivity, R.font.archivo_semibold)
            setTextColor(if (primary) c(R.color.on_brand) else c(R.color.text_primary))
            background = ContextCompat.getDrawable(this@DashEditorActivity, if (primary) R.drawable.ms_cta else R.drawable.ms_chip)
            setPadding(px(10), px(10), px(10), px(10))
            setOnClickListener { onClick() }
            tools.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        }
        tool(R.string.de_add) { addWidget() }
        tool(R.string.de_size) { cycleSize() }
        tool(R.string.de_delete) { deleteSelected() }
        tool(R.string.de_bg) { pickBackground() }
        tool(R.string.de_undo) { doUndo() }
        tool(R.string.de_themes) { themesMenu() }
        tool(R.string.de_reset) { confirmReset() }
        tool(R.string.de_save, primary = true) { save() }
        root.addView(ScrollView(this).apply { addView(tools) }, LinearLayout.LayoutParams(px(176), -1))

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) { finish(); return }
                MaterialAlertDialogBuilder(this@DashEditorActivity)
                    .setTitle(R.string.de_unsaved)
                    .setPositiveButton(R.string.de_save) { _, _ -> save(); finish() }
                    .setNegativeButton(R.string.de_discard) { _, _ -> finish() }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            }
        })
        LogBus.log("[DASH-EDIT] opened · '${dash.name}' · ${dash.widgets.size} widgets")
    }

    // ---- edits ----

    private fun checkpoint() {
        undo.addLast(dash.copyDeep())
        while (undo.size > 40) undo.removeFirst()
        dirty = true
    }

    private fun doUndo() {
        val prev = undo.removeLastOrNull() ?: run { toast(R.string.de_nothing_undo); return }
        dash = prev; preview.selected = null; dirty = true; refresh()
    }

    private fun refresh() {
        val s = preview.selected
        selLabel.text = if (s == null) getString(R.string.de_hint)
            else getString(R.string.de_selected, getString(s.type.label), sizeName(s.size))
        preview.invalidate()
    }

    private fun sizeName(size: Int) = getString(when (size) { 0 -> R.string.de_size_s; 2 -> R.string.de_size_l; else -> R.string.de_size_m })

    private fun addWidget() {
        val types = DashWidget.Type.values()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_add)
            .setItems(types.map { getString(it.label) }.toTypedArray()) { _, i ->
                val t = types[i]
                val add = { txt: String ->
                    checkpoint()
                    val w = DashWidget(t, 0.5f, 0.5f, 1, txt)
                    dash.widgets += w
                    preview.selected = w
                    refresh()
                }
                if (t == DashWidget.Type.TEXT) askText("") { add(it) } else add("")
            }
            .show()
    }

    private fun askText(start: String, done: (String) -> Unit) {
        val e = EditText(this).apply { setText(start); setSingleLine(); hint = getString(R.string.dw_text_sample) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_edit_text)
            .setView(FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(e) })
            .setPositiveButton(android.R.string.ok) { _, _ -> done(e.text.toString().take(40)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cycleSize() {
        val s = preview.selected ?: run { toast(R.string.de_pick_first); return }
        checkpoint(); s.size = (s.size + 1) % 3; refresh()
    }

    private fun deleteSelected() {
        val s = preview.selected ?: run { toast(R.string.de_pick_first); return }
        checkpoint(); dash.widgets.removeAll { it === s }; preview.selected = null; refresh()
    }

    private fun widgetOptions(w: DashWidget) {
        val opts = mutableListOf(
            getString(R.string.de_size_s) to { checkpoint(); w.size = 0; refresh() },
            getString(R.string.de_size_m) to { checkpoint(); w.size = 1; refresh() },
            getString(R.string.de_size_l) to { checkpoint(); w.size = 2; refresh() },
            getString(R.string.de_center_h) to { checkpoint(); w.x = 0.5f; refresh() },
            getString(R.string.de_duplicate) to {
                checkpoint(); val d = w.copy(x = (w.x + 0.06f).coerceAtMost(0.95f), y = (w.y + 0.08f).coerceAtMost(0.95f))
                dash.widgets += d; preview.selected = d; refresh()
            },
        )
        if (w.type == DashWidget.Type.TEXT) opts += getString(R.string.de_edit_text) to { askText(w.text) { checkpoint(); w.text = it; refresh() } }
        opts += getString(R.string.de_delete) to { checkpoint(); dash.widgets.removeAll { it === w }; preview.selected = null; refresh() }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(w.type.label))
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .show()
    }

    private fun pickBackground() {
        val keys = listOf("glow", "art", "photo", "black")
        val names = arrayOf(getString(R.string.de_bg_glow), getString(R.string.de_bg_art), getString(R.string.de_bg_photo), getString(R.string.de_bg_black), getString(R.string.de_dim))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_bg)
            .setItems(names) { _, i ->
                when {
                    i == 4 -> dimDialog()
                    keys[i] == "photo" -> photoPicker.launch("image/*")
                    else -> { checkpoint(); dash.bg = keys[i]; refresh() }
                }
            }
            .show()
    }

    private fun dimDialog() {
        val before = dash.dim
        val bar = SeekBar(this).apply { max = 90; progress = (dash.dim * 100).roundToInt() }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { dash.dim = p / 100f; preview.invalidate() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_dim)
            .setView(FrameLayout(this).apply { setPadding(pad, pad, pad, 0); addView(bar) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val now = dash.dim; dash.dim = before; checkpoint(); dash.dim = now; refresh()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> dash.dim = before; refresh() }
            .show()
    }

    /** Copy the picked picture into app storage, scaled to ≤1280 px wide (the dash is 1024×425). */
    private fun importPhoto(uri: Uri) {
        Thread {
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("decode")
                val out = if (bmp.width > 1280) Bitmap.createScaledBitmap(bmp, 1280, (bmp.height * 1280f / bmp.width).toInt(), true) else bmp
                DashTheme.photoFile(this).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                true
            } catch (e: Exception) { LogBus.log("[DASH-EDIT] photo failed: ${e.message}"); false }
            runOnUiThread {
                if (ok) { checkpoint(); dash.bg = "photo"; preview.renderer = DashRenderer(this); refresh(); dimDialog() }
                else toast(R.string.de_photo_fail)
            }
        }.start()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_reset)
            .setMessage(R.string.de_reset_confirm)
            .setPositiveButton(R.string.de_reset) { _, _ ->
                checkpoint(); dash = DashTheme.cockpit(this).also { it.bg = dash.bg; it.dim = dash.dim }
                preview.selected = null; refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        DashTheme.setCurrent(this, dash)
        dirty = false
        LogBus.log("[DASH-EDIT] saved '${dash.name}' · bg=${dash.bg} · ${dash.widgets.joinToString { "${it.type}@${"%.2f".format(it.x)},${"%.2f".format(it.y)}/${it.size}" }}")
        toast(if (MotoDash.active) R.string.de_saved_live else R.string.de_saved)
    }

    // ---- saved themes ----

    private fun themesMenu() {
        val saved = DashTheme.saved(this)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += getString(R.string.de_save_as) to { saveAs() }
        saved.forEach { t -> items += "▸ " + t.name to { loadTheme(t) } }
        if (saved.isNotEmpty()) items += getString(R.string.de_delete_theme) to { deleteThemeMenu() }
        items += getString(R.string.de_share) to { shareTheme() }
        items += getString(R.string.de_import) to { importTheme() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_themes)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun saveAs() {
        val e = EditText(this).apply { setText(dash.name); setSingleLine(); hint = getString(R.string.de_name_hint) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_save_as)
            .setView(FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(e) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = e.text.toString().trim().take(30).ifBlank { getString(R.string.dt_default_name) }
                dash.name = n
                DashTheme.save(this, dash)
                save()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadTheme(t: DashTheme) {
        checkpoint(); dash = t.copyDeep(); preview.selected = null; refresh()
    }

    private fun deleteThemeMenu() {
        val saved = DashTheme.saved(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_delete_theme)
            .setItems(saved.map { it.name }.toTypedArray()) { _, i -> DashTheme.delete(this, saved[i].name); toast(R.string.de_deleted) }
            .show()
    }

    /** Themes travel as text (a background photo stays on this phone) — paste in LINE, import on another phone. */
    private fun shareTheme() {
        val json = dash.json().toString()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "MOTOSCREEN-DASH:$json")
        }, getString(R.string.de_share)))
    }

    private fun importTheme() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val raw = text.substringAfter("MOTOSCREEN-DASH:", text).trim()
        val t = runCatching { DashTheme.from(JSONObject(raw)) }.getOrNull()?.takeIf { it.widgets.isNotEmpty() }
        if (t == null) { toast(R.string.de_import_bad); return }
        if (t.bg == "photo" && !DashTheme.photoFile(this).exists()) t.bg = "glow"
        loadTheme(t); toast(R.string.de_imported)
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()

    // ---- the preview / drag surface ----

    @SuppressLint("ViewConstructor")
    private inner class PreviewView(ctx: Context) : View(ctx) {
        var renderer = DashRenderer(ctx)
        var selected: DashWidget? = null
        private var dragging: DashWidget? = null
        private var grabDx = 0f
        private var grabDy = 0f
        private var moved = false
        private var guideX = false
        private var guideY = false
        private val frame = RectF()
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        }
        private val guide = Paint().apply { color = Color.argb(150, 255, 197, 71); strokeWidth = resources.displayMetrics.density }
        private val gridPaint = Paint().apply { color = Color.argb(28, 255, 255, 255) }
        private val pad = 10 * resources.displayMetrics.density

        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (moved) return
                val w = hitAt(e.x, e.y) ?: return
                dragging = null; selected = w; refresh(); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                widgetOptions(w)
            }
        })

        /** Keep the 1024:425 dash shape, centred in whatever space we get. */
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val ratio = 1024f / 425f
            var fw = w.toFloat(); var fh = fw / ratio
            if (fh > h) { fh = h.toFloat(); fw = fh * ratio }
            frame.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
        }

        private fun hitAt(x: Float, y: Float) = renderer.hit(x - frame.left, y - frame.top, pad)

        override fun onDraw(c: Canvas) {
            if (frame.width() <= 0) return
            c.save()
            c.clipRect(frame)
            c.translate(frame.left, frame.top)
            renderer.draw(c, frame.width(), frame.height(), dash, null, sample = true)
            if (dragging != null) {
                for (i in 1 until 8) c.drawLine(frame.width() * i / 8, 0f, frame.width() * i / 8, frame.height(), gridPaint)
                for (i in 1 until 4) c.drawLine(0f, frame.height() * i / 4, frame.width(), frame.height() * i / 4, gridPaint)
                if (guideX) c.drawLine(frame.width() / 2, 0f, frame.width() / 2, frame.height(), guide)
                if (guideY) c.drawLine(0f, frame.height() / 2, frame.width(), frame.height() / 2, guide)
            }
            selected?.let { s ->
                renderer.boundsOf(s)?.let { r ->
                    outline.color = MotoScreen.accent(context).swatch.toInt()
                    c.drawRoundRect(RectF(r).apply { inset(-pad / 2, -pad / 2) }, pad, pad, outline)
                }
            }
            c.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            gestures.onTouchEvent(e)
            val fw = frame.width(); val fh = frame.height()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    val w = hitAt(e.x, e.y)
                    selected = w; dragging = w
                    if (w != null) {
                        grabDx = (frame.left + w.x * fw) - e.x
                        grabDy = (frame.top + w.y * fh) - e.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    refresh()
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = dragging ?: return true
                    var nx = ((e.x + grabDx - frame.left) / fw).coerceIn(0.02f, 0.98f)
                    var ny = ((e.y + grabDy - frame.top) / fh).coerceIn(0.03f, 0.97f)
                    if (!moved && (abs(nx - w.x) * fw > pad || abs(ny - w.y) * fh > pad)) { moved = true; checkpoint() }
                    if (!moved) return true
                    // Snap: centre lines first (shown as a guide), else a fine 1/40 grid.
                    guideX = abs(nx - 0.5f) < 0.015f; guideY = abs(ny - 0.5f) < 0.025f
                    nx = if (guideX) 0.5f else (nx * 40).roundToInt() / 40f
                    ny = if (guideY) 0.5f else (ny * 40).roundToInt() / 40f
                    w.x = nx; w.y = ny
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = null; guideX = false; guideY = false; refresh()
                }
            }
            return true
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, DashEditorActivity::class.java))
    }
}
