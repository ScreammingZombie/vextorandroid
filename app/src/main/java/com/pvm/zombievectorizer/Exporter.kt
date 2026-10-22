package com.pvm.zombievectorizer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.pvm.zombievectorizer.engine.Svg
import com.pvm.zombievectorizer.engine.VectorImage
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Format(val label: String, val desc: String, val ext: String, val mime: String, val suffix: String) {
    SVG("SVG", "Editable. Se agranda sin perder calidad.", "svg", "image/svg+xml", ""),
    PDF("PDF", "Listo para imprimir/enviar.", "pdf", "application/pdf", ""),
    PNG("PNG normal", "Liviana, para compartir.", "png", "image/png", ""),
    PNG_HD("PNG alta resolución", "Enorme y nítido, para imprimir.", "png", "image/png", "_hd")
}

object Exporter {
    private const val FOLDER = "Zombie Vectorizer"

    fun write(v: VectorImage, f: Format, out: OutputStream) {
        when (f) {
            Format.SVG -> out.write(Svg.write(v).toByteArray(Charsets.UTF_8))
            Format.PDF -> Renderer.writePdf(v, out)
            Format.PNG -> Renderer.writePng(v, Renderer.fitLongSide(v, 1600, 4_000_000L), out)
            Format.PNG_HD -> Renderer.writePng(v, Renderer.fitLongSide(v, 4096, 16_000_000L), out)
        }
    }

    fun fileName(f: Format): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "zombie_vector_$stamp${f.suffix}.${f.ext}"
    }

    /** Guarda en Descargas/Zombie Vectorizer. Devuelve el nombre del archivo. */
    fun saveToDownloads(ctx: Context, v: VectorImage, f: Format): String {
        val name = fileName(f)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, f.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo")
        try {
            resolver.openOutputStream(uri)!!.use { write(v, f, it) }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return "Descargas/$FOLDER/$name"
    }

    /** Arma el archivo en caché y devuelve la intención de compartir. */
    fun shareIntent(ctx: Context, v: VectorImage, f: Format): Intent {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, fileName(f))
        file.outputStream().use { write(v, f, it) }
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = f.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
