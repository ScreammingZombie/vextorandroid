package com.pvm.zombievectorizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.pvm.zombievectorizer.engine.VectorImage
import java.io.OutputStream
import kotlin.math.max

object Renderer {

    private fun draw(v: VectorImage, canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (layer in v.layers) {
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            for (c in layer.contours) {
                path.moveTo(c[0], c[1])
                var i = 2
                while (i + 5 < c.size) {
                    path.cubicTo(c[i], c[i + 1], c[i + 2], c[i + 3], c[i + 4], c[i + 5])
                    i += 6
                }
                path.close()
            }
            paint.color = layer.color
            canvas.drawPath(path, paint)
        }
    }

    /** Dibuja el vector en un bitmap cuyo lado más largo mide [longSide] píxeles. */
    fun toBitmap(v: VectorImage, longSide: Int): Bitmap {
        val k = longSide.toFloat() / max(v.width, v.height)
        val w = max(1, Math.round(v.width * k))
        val h = max(1, Math.round(v.height * k))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(w.toFloat() / v.width, h.toFloat() / v.height)
        draw(v, canvas)
        return bmp
    }

    fun writePng(v: VectorImage, longSide: Int, out: OutputStream) {
        val bmp = toBitmap(v, longSide)
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    /** PDF con las formas como vectores reales (no una foto dentro del PDF). */
    fun writePdf(v: VectorImage, out: OutputStream) {
        val k = 842f / max(v.width, v.height)
        val pw = max(1, Math.round(v.width * k))
        val ph = max(1, Math.round(v.height * k))
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            page.canvas.scale(pw.toFloat() / v.width, ph.toFloat() / v.height)
            draw(v, page.canvas)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun fitLongSide(v: VectorImage, wanted: Int, maxPixels: Long): Int {
        val k = wanted.toFloat() / max(v.width, v.height)
        val px = (v.width * k).toLong() * (v.height * k).toLong()
        return if (px <= maxPixels) wanted else (wanted * Math.sqrt(maxPixels.toDouble() / px)).toInt()
    }

}
