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
    /** v19: a picture placed as a widget (rider, logo, sticker). */
    private val stickerPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importSticker(it) } }

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

        // ---- right: tools (two columns, Save pinned at the bottom) ----
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), 0, 0, 0) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label); textSize = 13f; gravity = Gravity.CENTER; maxLines = 2
            typeface = ResourcesCompat.getFont(this@DashEditorActivity, R.font.archivo_semibold)
            setTextColor(if (primary) c(R.color.on_brand) else c(R.color.text_primary))
            background = ContextCompat.getDrawable(this@DashEditorActivity, if (primary) R.drawable.ms_cta else R.drawable.ms_chip)
            setPadding(px(6), px(9), px(6), px(9))
            setOnClickListener { onClick() }
        }
        fun row(a: Pair<Int, () -> Unit>, b: Pair<Int, () -> Unit>) {
            tools.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(a.first, false, a.second), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = px(3) })
                addView(button(b.first, false, b.second), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(3) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        }
        row(R.string.de_add to { addWidget() }, R.string.de_presets to { presetGallery() })
        row(R.string.de_style to { styleMenu() }, R.string.de_color to { colorMenu() })
        row(R.string.de_size to { cycleSize() }, R.string.de_layer to { layerMenu() })
        row(R.string.de_bg to { pickBackground() }, R.string.de_font to { fontMenu() })
        row(R.string.de_speed to { speedMenu() }, R.string.de_undo to { doUndo() })
        row(R.string.de_themes to { themesMenu() }, R.string.de_delete to { deleteSelected() })
        row(R.string.de_reset to { confirmReset() }, R.string.de_help to { helpDialog() })
        side.addView(ScrollView(this).apply { addView(tools) }, LinearLayout.LayoutParams(-1, 0, 1f))
        side.addView(button(R.string.de_save, true) { save() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        root.addView(side, LinearLayout.LayoutParams(px(232), -1))

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
            else getString(R.string.de_selected, getString(s.type.label), sizeName(s.size)) +
                (s.type.styles.getOrNull(s.style)?.let { " · " + getString(it) } ?: "")
        preview.invalidate()
    }

    private fun sizeName(size: Int) = getString(when (size) { 0 -> R.string.de_size_s; 2 -> R.string.de_size_l; else -> R.string.de_size_m })

    private fun addWidget() {
        val icons = arrayOf("🏍", "🧭", "ℹ️", "🎨")
        val types = DashWidget.Type.values().sortedBy { it.group }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_add)
            .setItems(types.map { icons[it.group] + "  " + getString(it.label) }.toTypedArray()) { _, i ->
                val t = types[i]
                when (t) {
                    DashWidget.Type.TEXT -> askText("") { place(DashWidget(t, 0.5f, 0.5f, 1, it)) }
                    DashWidget.Type.PHOTO -> stickerPicker.launch("image/*")
                    else -> place(DashWidget(t, 0.5f, 0.5f, 1))
                }
            }
            .show()
    }

    /** New widgets land in the middle; panels and lines go behind everything else. */
    private fun place(w: DashWidget) {
        checkpoint()
        if (w.type == DashWidget.Type.PANEL || w.type == DashWidget.Type.LINE || w.type == DashWidget.Type.MAP || w.type == DashWidget.Type.AA) dash.widgets.add(0, w) else dash.widgets += w
        preview.selected = w
        refresh()
    }

    private fun styleMenu() {
        val w = preview.selected ?: run { toast(R.string.de_pick_first); return }
        if (w.type.styles.isEmpty()) { toast(R.string.de_no_styles); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.de_style) + " · " + getString(w.type.label))
            .setSingleChoiceItems(w.type.styles.map { getString(it) }.toTypedArray(), w.style) { d, i ->
                checkpoint(); w.style = i; refresh(); d.dismiss()
            }
            .show()
    }

    /** With a widget selected: its colour. Without: the theme's highlight and text colours. */
    private fun colorMenu() {
        val w = preview.selected
        if (w != null) {
            colorPicker(getString(R.string.de_color) + " · " + getString(w.type.label), w.color) { checkpoint(); w.color = it; refresh() }
            return
        }
        val items = arrayOf(getString(R.string.de_color_accent), getString(R.string.de_color_ink))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_color)
            .setItems(items) { _, i ->
                if (i == 0) colorPicker(items[0], dash.accent) { checkpoint(); dash.accent = it; refresh() }
                else colorPicker(items[1], dash.ink) { checkpoint(); dash.ink = it; refresh() }
            }
            .show()
    }

    private fun colorPicker(title: String, current: Long, pick: (Long) -> Unit) {
        val dp = resources.displayMetrics.density
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0) }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val all = longArrayOf(0L) + DashTheme.COLORS
        all.toList().chunked(7).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { col ->
                row.addView(TextView(this).apply {
                    val size = (36 * dp).toInt()
                    gravity = Gravity.CENTER; textSize = 12f
                    text = if (col == 0L) "A" else if (col == current) "✓" else ""
                    setTextColor(if (col == 0L) ContextCompat.getColor(this@DashEditorActivity, R.color.text_primary) else Color.BLACK)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        if (col == 0L) setStroke((2 * dp).toInt(), Color.GRAY) else setColor(col.toInt())
                        if (col == current && col != 0L) setStroke((3 * dp).toInt(), Color.WHITE)
                    }
                    setOnClickListener { pick(col); dialog?.dismiss() }
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt()) }
                })
            }
            grid.addView(row)
        }
        grid.addView(TextView(this).apply {
            setText(R.string.de_color_auto); textSize = 12f
            setTextColor(ContextCompat.getColor(this@DashEditorActivity, R.color.text_secondary)); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(grid).setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun fontMenu() {
        val fonts = DashFont.values()
        val dp = resources.displayMetrics.density
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fonts.forEach { f ->
            list.addView(TextView(this).apply {
                text = (if (f.ordinal == dash.font) "✓  " else "    ") + getString(f.label) + "   " + "88 km/h 16:05"
                textSize = 20f
                typeface = try { ResourcesCompat.getFont(this@DashEditorActivity, f.num) } catch (_: Exception) { null }
                setTextColor(ContextCompat.getColor(this@DashEditorActivity, R.color.text_primary))
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                setOnClickListener { checkpoint(); dash.font = f.ordinal; refresh(); dialog?.dismiss() }
            })
        }
        dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.de_font).setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun speedMenu() {
        val items = arrayOf(getString(R.string.de_gauge_max, dash.max), getString(if (dash.warn > 0) R.string.de_warn_on else R.string.de_warn_off, dash.warn))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_speed)
            .setItems(items) { _, i ->
                if (i == 0) {
                    val opts = intArrayOf(100, 120, 140, 160, 180, 200)
                    MaterialAlertDialogBuilder(this).setTitle(R.string.de_gauge_max_t)
                        .setSingleChoiceItems(opts.map { "$it km/h" }.toTypedArray(), opts.indexOf(dash.max)) { d, j -> checkpoint(); dash.max = opts[j]; refresh(); d.dismiss() }
                        .show()
                } else {
                    val opts = intArrayOf(0, 60, 80, 90, 100, 110, 120, 140)
                    MaterialAlertDialogBuilder(this).setTitle(R.string.de_warn_t)
                        .setSingleChoiceItems(opts.map { if (it == 0) getString(R.string.de_off) else "$it km/h" }.toTypedArray(), opts.indexOf(dash.warn)) { d, j -> checkpoint(); dash.warn = opts[j]; refresh(); d.dismiss() }
                        .show()
                }
            }
            .show()
    }

    private fun layerMenu() {
        val w = preview.selected ?: run { toast(R.string.de_pick_first); return }
        val items = arrayOf(getString(R.string.de_front), getString(R.string.de_back))
        MaterialAlertDialogBuilder(this).setTitle(R.string.de_layer)
            .setItems(items) { _, i ->
                checkpoint()
                dash.widgets.removeAll { it === w }
                if (i == 0) dash.widgets += w else dash.widgets.add(0, w)
                refresh()
            }.show()
    }

    /** Ready-made themes as pictures — tap one to start from it. */
    private fun presetGallery() {
        val dp = resources.displayMetrics.density
        val r = DashRenderer(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0) }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val widthPx = (resources.displayMetrics.widthPixels * 0.55f).toInt().coerceIn(360, 900)
        (DashTheme.presets(this) + DashTheme.saved(this)).forEach { t ->
            list.addView(TextView(this).apply {
                text = t.name; textSize = 14f
                setTextColor(ContextCompat.getColor(this@DashEditorActivity, R.color.text_primary)); setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
            })
            list.addView(android.widget.ImageView(this).apply {
                setImageBitmap(r.thumbnail(t, widthPx))
                adjustViewBounds = true
                setOnClickListener { loadTheme(t); dialog?.dismiss() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.de_presets).setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun helpDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.de_help).setMessage(R.string.de_help_body)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    /** Sticker picture: centre-cropped square, 512 px. */
    private fun importSticker(uri: Uri) {
        Thread {
            val name = "dash_img_${System.currentTimeMillis()}.jpg"
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("decode")
                val side = minOf(bmp.width, bmp.height)
                val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val out = if (side > 512) Bitmap.createScaledBitmap(sq, 512, 512, true) else sq
                DashTheme.imageFile(this, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                true
            } catch (e: Exception) { LogBus.log("[DASH-EDIT] sticker failed: ${e.message}"); false }
            runOnUiThread {
                if (ok) place(DashWidget(DashWidget.Type.PHOTO, 0.5f, 0.5f, 1, img = name))
                else toast(R.string.de_photo_fail)
            }
        }.start()
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
        if (w.type.styles.isNotEmpty()) opts += getString(R.string.de_style) + "…" to { styleMenu() }
        opts += getString(R.string.de_color) + "…" to { colorMenu() }
        opts += getString(R.string.de_front) to { checkpoint(); dash.widgets.removeAll { it === w }; dash.widgets += w; refresh() }
        opts += getString(R.string.de_back) to { checkpoint(); dash.widgets.removeAll { it === w }; dash.widgets.add(0, w); refresh() }
        if (w.type == DashWidget.Type.TEXT) opts += getString(R.string.de_edit_text) to { askText(w.text) { checkpoint(); w.text = it; refresh() } }
        opts += getString(R.string.de_delete) to { checkpoint(); dash.widgets.removeAll { it === w }; preview.selected = null; refresh() }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(w.type.label))
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .show()
    }

    private fun pickBackground() {
        val keys = DashTheme.BACKGROUNDS
        val names = keys.map { getString(bgName(it)) } + getString(R.string.de_dim)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.de_bg)
            .setItems(names.toTypedArray()) { _, i ->
                when {
                    i == keys.size -> dimDialog()
                    keys[i] == "photo" -> photoPicker.launch("image/*")
                    else -> { checkpoint(); dash.bg = keys[i]; refresh() }
                }
            }
            .show()
    }

    private fun bgName(k: String) = when (k) {
        "sunset" -> R.string.de_bg_sunset; "ocean" -> R.string.de_bg_ocean; "racing" -> R.string.de_bg_racing
        "forest" -> R.string.de_bg_forest; "carbon" -> R.string.de_bg_carbon; "grid" -> R.string.de_bg_grid
        "black" -> R.string.de_bg_black; "art" -> R.string.de_bg_art; "photo" -> R.string.de_bg_photo
        else -> R.string.de_bg_glow
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
                checkpoint(); val old = dash
                dash = DashTheme.cockpit(this).also { it.bg = old.bg; it.dim = old.dim; it.accent = old.accent; it.ink = old.ink; it.font = old.font; it.warn = old.warn; it.max = old.max }
                preview.selected = null; refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        DashTheme.setCurrent(this, dash)
        dirty = false
        LogBus.log("[DASH-EDIT] saved '${dash.name}' · bg=${dash.bg} · ${dash.widgets.joinToString { "${it.type}@${"%.2f".format(it.x)},${"%.2f".format(it.y)}/${it.size}" }}")
        val wantsAa = dash.widgets.any { it.type == DashWidget.Type.AA }
        toast(when {
            MotoDash.active && wantsAa -> R.string.de_saved_reconnect
            MotoDash.active || AaDashOverlay.enabled -> R.string.de_saved_live
            else -> R.string.de_saved
        })
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
