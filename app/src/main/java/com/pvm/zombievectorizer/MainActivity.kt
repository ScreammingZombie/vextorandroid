package com.pvm.zombievectorizer

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.pvm.zombievectorizer.engine.Analysis
import com.pvm.zombievectorizer.engine.CancelledException
import com.pvm.zombievectorizer.engine.Mode
import com.pvm.zombievectorizer.engine.Progress
import com.pvm.zombievectorizer.engine.Svg
import com.pvm.zombievectorizer.engine.VectorImage
import com.pvm.zombievectorizer.engine.Vectorizer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    private enum class Screen { SPLASH, HOME, BUSY, MODES, PROCESSING, RESULT, HISTORY }

    private lateinit var root: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private var screen = Screen.SPLASH
    private var titleFace: Typeface? = null
    private var buttonFace: Typeface? = null

    private var original: Bitmap? = null
    private var pixels: IntArray? = null
    private var analysis: Analysis? = null

    private var result: VectorImage? = null
    private var resultMode = Mode.FAST
    private var resultOrig: Bitmap? = null
    private var resultPreview: Bitmap? = null

    @Volatile private var cancelFlag = false
    private var cameraUri: Uri? = null

    private val reqPick = 1
    private val reqCamera = 2

    // ------------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFonts()
        root = FrameLayout(this).apply { setBackgroundColor(Pal.BG) }
        setContentView(root)
        showSplash()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME, Screen.SPLASH -> super.onBackPressed()
            Screen.PROCESSING -> cancelFlag = true
            Screen.BUSY -> {}
            else -> showHome()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = if (requestCode == reqPick) data?.data else cameraUri
        if (uri != null) loadImage(uri)
    }

    // ------------------------------------------------------------------ utilidades de UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadFonts() {
        try {
            val names = assets.list("fonts")?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) } ?: emptyList()
            names.firstOrNull { it.contains("dark", true) }?.let {
                titleFace = Typeface.createFromAsset(assets, "fonts/$it")
            }
            names.firstOrNull { it.contains("scary", true) }?.let {
                buttonFace = Typeface.createFromAsset(assets, "fonts/$it")
            }
        } catch (_: Throwable) {
        }
    }

    /** Usa la fuente zombie solo si tiene todas las letras del texto (si no, la del sistema en negrita). */
    private fun face(tv: TextView, text: String, f: Typeface?) {
        if (f != null) {
            val p = Paint().apply { typeface = f }
            if (text.all { it.isWhitespace() || p.hasGlyph(it.toString()) }) {
                tv.typeface = f
                return
            }
        }
        tv.typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    private fun text(s: String, size: Float, color: Int, center: Boolean = false, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (center) gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   weight: Float = 0f, m: Int = 6): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(if (primary) Pal.BG else Pal.GREEN)
            background = if (primary) rounded(Pal.GREEN, dp(18)) else rounded(Pal.PANEL2, dp(18), dp(2), Pal.GREEN)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            face(this, label, buttonFace)
            setOnClickListener { onClick() }
        }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun header(small: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val title = TextView(this@MainActivity).apply {
            text = "ZOMBIE VECTORIZER"
            setTextColor(Pal.GREEN)
            gravity = Gravity.CENTER
            maxLines = 1
            face(this, "ZOMBIE VECTORIZER", titleFace)
            setAutoSizeTextTypeUniformWithConfiguration(14, if (small) 30 else 44, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (small) 40 else 56)))
        addView(text("By P.V.M.", 11f, Pal.GRAY, center = true))
    }

    private fun setScreen(v: View, scroll: Boolean = true) {
        root.removeAllViews()
        if (scroll) {
            val sv = ScrollView(this).apply { isFillViewport = true; addView(v) }
            root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        } else {
            root.addView(v, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun modeName(m: Mode) = if (m == Mode.FAST) "Rápido" else "Máxima calidad"

    // ------------------------------------------------------------------ splash y pantalla de inicio
    private fun showSplash() {
        screen = Screen.SPLASH
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Pal.BG)
        }
        root.removeAllViews()
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
        ui.postDelayed({ if (screen == Screen.SPLASH) showHome() }, 3000)
    }

    private fun showHome() {
        screen = Screen.HOME
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = page()
        p.addView(header(false), lp(m = 0))
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        p.addView(logo, lp(h = dp(220), m = 14))
        p.addView(text("Convertí cualquier imagen a vector. Todo funciona sin internet.", 14f, Pal.TEXT, center = true), lp(m = 8))
        p.addView(button("ELEGIR IMAGEN") { pickImage() }, lp())
        p.addView(button("SACAR FOTO") { takePhoto() }, lp())
        p.addView(button("HISTORIAL", primary = false) { showHistory() }, lp())
        setScreen(p)
    }

    private fun showBusy(msg: String) {
        screen = Screen.BUSY
        val p = page().apply { gravity = Gravity.CENTER }
        p.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(Pal.GREEN) }, lp(dp(56), dp(56)))
        p.addView(text(msg, 16f, Pal.TEXT, center = true), lp())
        setScreen(p, scroll = false)
    }

    // ------------------------------------------------------------------ cargar imagen
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, reqPick)
        } catch (e: ActivityNotFoundException) {
            toast("No encontré una app para elegir imágenes")
        }
    }

    private fun takePhoto() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "foto.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            cameraUri = uri
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("foto", uri)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, reqCamera)
        } catch (e: Exception) {
            toast("No se pudo abrir la cámara")
        }
    }

    private fun loadImage(uri: Uri) {
        showBusy("Cargando imagen…")
        Thread {
            try {
                val bmp = decode(uri)
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val a = Vectorizer.analyze(px, w, h)
                ui.post {
                    original = bmp
                    pixels = px
                    analysis = a
                    showModes()
                }
            } catch (t: Throwable) {
                ui.post {
                    toast(if (t is OutOfMemoryError) "La imagen es demasiado grande para este teléfono" else "No se pudo abrir la imagen")
                    showHome()
                }
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap {
        val cr = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val long = max(bounds.outWidth, bounds.outHeight)
        while (long / sample > 3000) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("No se pudo leer la imagen")
        val orientation = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (_: Throwable) {
            1
        }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    // ------------------------------------------------------------------ elegir modo
    private fun showModes() {
        screen = Screen.MODES
        val a = analysis!!
        val p = page()
        p.addView(header(true), lp(m = 0))
        val preview = ImageView(this).apply {
            setImageBitmap(original)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Pal.PANEL, dp(14))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        p.addView(preview, lp(h = dp(200)))
        p.addView(text("${original!!.width} × ${original!!.height} px", 12f, Pal.GRAY, center = true), lp(m = 2))
        if (a.isPhoto) {
            val warn = text("Las fotos pueden dar resultados artísticos y archivos pesados.", 13f, Pal.WARN, center = true)
            warn.background = rounded(Pal.PANEL, dp(12), dp(1), Pal.WARN)
            warn.setPadding(dp(10), dp(8), dp(10), dp(8))
            p.addView(warn, lp())
        }
        p.addView(text("¿Cómo querés convertirla?", 16f, Pal.TEXT, center = true, bold = true), lp(m = 8))
        p.addView(modeCard(Mode.FAST, "RÁPIDO",
            "Convierte al instante. Ideal para logos y dibujos nítidos.", a.recommended == Mode.FAST), lp())
        p.addView(modeCard(Mode.MAX, "MÁXIMA CALIDAD",
            "Mejora la imagen primero y después la convierte. Tarda más, pero rinde mejor con imágenes borrosas o chicas.",
            a.recommended == Mode.MAX), lp())
        p.addView(button("VOLVER", primary = false) { showHome() }, lp())
        setScreen(p)
    }

    private fun modeCard(mode: Mode, title: String, desc: String, recommended: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Pal.PANEL, dp(18), dp(if (recommended) 3 else 1), if (recommended) Pal.GREEN else Pal.DARKGREEN)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val t = TextView(this@MainActivity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Pal.GREEN)
                face(this, title, buttonFace)
            }
            addView(t)
            if (recommended) addView(text("★ RECOMENDADO", 12f, Pal.WARN, bold = true))
            addView(text(desc, 14f, Pal.TEXT))
            setOnClickListener { startConversion(mode) }
        }

    // ------------------------------------------------------------------ convertir
    private fun startConversion(mode: Mode) {
        screen = Screen.PROCESSING
        cancelFlag = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = page().apply { gravity = Gravity.CENTER_HORIZONTAL }
        p.addView(header(true), lp(m = 0))
        p.addView(text("Convirtiendo… (${modeName(mode)})", 20f, Pal.GREEN, center = true, bold = true), lp(m = 24))
        val stage = text("Empezando", 15f, Pal.TEXT, center = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Pal.GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(Pal.PANEL2)
        }
        val pct = text("0%", 14f, Pal.GRAY, center = true)
        p.addView(stage, lp())
        p.addView(bar, lp(h = dp(14)))
        p.addView(pct, lp())
        p.addView(text("No cierres la app mientras convierte.", 12f, Pal.GRAY, center = true), lp(m = 10))
        p.addView(button("CANCELAR", primary = false) { cancelFlag = true }, lp(m = 16))
        setScreen(p)

        val src = pixels!!
        val bmp = original!!
        val w = bmp.width
        val h = bmp.height
        Thread {
            try {
                val v = Vectorizer.run(src, w, h, mode, object : Progress {
                    override fun update(stage2: String, fraction: Float) {
                        ui.post {
                            stage.text = stage2
                            bar.progress = (fraction * 100).toInt()
                            pct.text = "${(fraction * 100).toInt()}%"
                        }
                    }

                    override fun cancelled() = cancelFlag
                })
                ui.post { stage.text = "Preparando vista previa" }
                val preview = Renderer.toBitmap(v, 1600)
                try {
                    History.add(applicationContext, v, mode, bmp)
                } catch (_: Throwable) {
                }
                ui.post {
                    result = v
                    resultMode = mode
                    resultOrig = bmp
                    resultPreview = preview
                    showResult()
                }
            } catch (e: CancelledException) {
                ui.post { showModes() }
            } catch (t: Throwable) {
                ui.post {
                    toast(if (t is OutOfMemoryError)
                        "Se quedó sin memoria. Probá con una imagen más chica o con el modo Rápido."
                    else "No se pudo convertir la imagen")
                    showModes()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ resultado
    private fun showResult() {
        screen = Screen.RESULT
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = result!!
        val p = page()
        p.addView(header(true), lp(m = 0))
        val cv = CompareView(this).apply {
            before = resultOrig
            after = resultPreview
        }
        p.addView(cv, lp(h = 0, weight = 1f, m = 8))
        p.addView(text("${modeName(resultMode)} · ${v.layers.size} capas · deslizá para comparar", 12f, Pal.GRAY, center = true), lp(m = 2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("GUARDAR") { chooseFormat { f -> save(f) } }, lp(h = ViewGroup.LayoutParams.WRAP_CONTENT, w = 0, weight = 1f))
        row.addView(button("COMPARTIR") { chooseFormat { f -> share(f) } }, lp(h = ViewGroup.LayoutParams.WRAP_CONTENT, w = 0, weight = 1f))
        p.addView(row, lp(m = 0))
        p.addView(button("NUEVA IMAGEN", primary = false) { showHome() }, lp())
        setScreen(p, scroll = false)
    }

    private fun chooseFormat(onChosen: (Format) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Pal.BG, dp(22), dp(2), Pal.GREEN)
        }
        box.addView(text("Elegí el formato", 18f, Pal.GREEN, center = true, bold = true), lp())
        val dlg = AlertDialog.Builder(this).setView(box).create()
        for (f in Format.values()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Pal.PANEL, dp(14), dp(1), Pal.DARKGREEN)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val t = TextView(this@MainActivity).apply {
                    text = f.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(Pal.GREEN)
                    face(this, f.label, buttonFace)
                }
                addView(t)
                addView(text(f.desc, 13f, Pal.TEXT))
                setOnClickListener { dlg.dismiss(); onChosen(f) }
            }
            box.addView(card, lp())
        }
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    private fun workingDialog(msg: String): AlertDialog {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Pal.BG, dp(18), dp(2), Pal.GREEN)
        }
        box.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(Pal.GREEN) }, lp(dp(40), dp(40)))
        box.addView(text(msg, 15f, Pal.TEXT), lp())
        val d = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
        return d
    }

    private fun save(f: Format) {
        val v = result ?: return
        val d = workingDialog("Guardando ${f.label}…")
        Thread {
            try {
                val where = Exporter.saveToDownloads(applicationContext, v, f)
                ui.post { d.dismiss(); toast("Guardado en $where") }
            } catch (t: Throwable) {
                ui.post { d.dismiss(); toast(if (t is OutOfMemoryError) "Sin memoria: probá con otro formato" else "No se pudo guardar") }
            }
        }.start()
    }

    private fun share(f: Format) {
        val v = result ?: return
        val d = workingDialog("Preparando ${f.label}…")
        Thread {
            try {
                val intent = Exporter.shareIntent(applicationContext, v, f)
                ui.post { d.dismiss(); startActivity(intent) }
            } catch (t: Throwable) {
                ui.post { d.dismiss(); toast(if (t is OutOfMemoryError) "Sin memoria: probá con otro formato" else "No se pudo compartir") }
            }
        }.start()
    }

    // ------------------------------------------------------------------ historial
    private fun showHistory() {
        screen = Screen.HISTORY
        val p = page()
        p.addView(header(true), lp(m = 0))
        p.addView(text("Historial", 20f, Pal.GREEN, center = true, bold = true), lp(m = 10))
        val items = History.list(this)
        if (items.isEmpty()) {
            p.addView(text("Todavía no hay conversiones.", 14f, Pal.GRAY, center = true), lp(m = 20))
        }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR"))
        for (e in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Pal.PANEL, dp(14), dp(1), Pal.DARKGREEN)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                try {
                    setImageBitmap(BitmapFactory.decodeFile(e.orig.path, BitmapFactory.Options().apply { inSampleSize = 4 }))
                } catch (_: Throwable) {
                }
            }
            row.addView(thumb, lp(dp(68), dp(68), m = 4))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(fmt.format(Date(e.id)), 15f, Pal.TEXT, bold = true))
            col.addView(text(modeName(e.mode), 13f, Pal.GRAY))
            row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 8))
            row.setOnClickListener { openEntry(e) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("¿Borrar esta conversión del historial?")
                    .setPositiveButton("Borrar") { _, _ -> History.delete(e); showHistory() }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
            p.addView(row, lp())
        }
        if (items.isNotEmpty()) p.addView(text("Mantené apretado un elemento para borrarlo.", 11f, Pal.GRAY, center = true), lp(m = 4))
        p.addView(button("VOLVER", primary = false) { showHome() }, lp(m = 12))
        setScreen(p)
    }

    private fun openEntry(e: History.Entry) {
        showBusy("Abriendo…")
        Thread {
            try {
                val v = Svg.parse(e.svg.readText(Charsets.UTF_8))
                val orig = BitmapFactory.decodeFile(e.orig.path)
                val preview = Renderer.toBitmap(v, 1600)
                ui.post {
                    result = v
                    resultMode = e.mode
                    resultOrig = orig
                    resultPreview = preview
                    showResult()
                }
            } catch (t: Throwable) {
                ui.post { toast("No se pudo abrir esta conversión"); showHistory() }
            }
        }.start()
    }
}
