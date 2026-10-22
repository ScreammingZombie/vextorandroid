package com.pvm.zombievectorizer

import android.content.Context
import android.graphics.Bitmap
import com.pvm.zombievectorizer.engine.Mode
import com.pvm.zombievectorizer.engine.Svg
import com.pvm.zombievectorizer.engine.VectorImage
import java.io.File

/** Historial simple: cada conversión guarda el SVG y una copia chica de la imagen original. */
object History {
    private const val MAX_ITEMS = 20

    class Entry(val id: Long, val mode: Mode, val svg: File, val orig: File)

    private fun dir(ctx: Context) = File(ctx.filesDir, "historial").apply { mkdirs() }

    fun add(ctx: Context, v: VectorImage, mode: Mode, original: Bitmap) {
        val id = System.currentTimeMillis()
        val base = "${id}_${mode.name}"
        val d = dir(ctx)
        File(d, "$base.svg").writeText(Svg.write(v), Charsets.UTF_8)
        val k = 1000f / maxOf(original.width, original.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(original, Math.round(original.width * k), Math.round(original.height * k), true) else original
        File(d, "$base.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        list(ctx).drop(MAX_ITEMS).forEach { delete(it) }
    }

    fun list(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        for (f in dir(ctx).listFiles().orEmpty()) {
            if (f.extension != "svg") continue
            val parts = f.nameWithoutExtension.split("_")
            val id = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val mode = if (parts.getOrNull(1) == "MAX") Mode.MAX else Mode.FAST
            val orig = File(f.parentFile, f.nameWithoutExtension + ".jpg")
            if (orig.exists()) out.add(Entry(id, mode, f, orig))
        }
        return out.sortedByDescending { it.id }
    }

    fun delete(e: Entry) {
        e.svg.delete(); e.orig.delete()
    }
}
