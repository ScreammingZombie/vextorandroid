package com.pvm.zombievectorizer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class IntBuf(cap: Int = 1024) {
    var a = IntArray(cap)
    var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
}

/** Traza el contorno (marching squares sobre una máscara suavizada) de las etiquetas >= j y lo ajusta con curvas. */
internal object Tracer {

    fun trace(labels: ByteArray, w: Int, h: Int, j: Int, x0: Int, y0: Int, x1: Int, y1: Int,
              A: FloatArray, B: FloatArray, passes: Int, err2: Double, win: Double, cornerDeg: Double,
              minArea: Double, cancelled: () -> Boolean): List<FloatArray> {
        val rx0 = max(x0 - 3, -3); val rx1 = min(x1 + 3, w + 2)
        val ry0 = max(y0 - 3, -3); val ry1 = min(y1 + 3, h + 2)
        val RW = rx1 - rx0 + 1; val RH = ry1 - ry0 + 1

        // 1) máscara binaria (con borde replicado y un anillo de ceros para cerrar siempre los contornos)
        for (ry in 0 until RH) {
            val iy = ry + ry0
            val base = ry * RW
            if (iy < -2 || iy > h + 1) { java.util.Arrays.fill(A, base, base + RW, 0f); continue }
            val row = iy.coerceIn(0, h - 1) * w
            for (rxi in 0 until RW) {
                val ix = rxi + rx0
                A[base + rxi] = if (ix < -2 || ix > w + 1) 0f else {
                    val l = labels[row + ix.coerceIn(0, w - 1)].toInt() and 0xFF
                    if (l >= j && l < 255) 1f else 0f
                }
            }
        }
        // 2) suavizado [1 2 1]/4 separable
        for (pass in 0 until passes) {
            for (y in 0 until RH) {
                val b = y * RW
                for (x in 0 until RW) {
                    val l = if (x > 0) A[b + x - 1] else 0f
                    val r = if (x < RW - 1) A[b + x + 1] else 0f
                    B[b + x] = 0.25f * l + 0.5f * A[b + x] + 0.25f * r
                }
            }
            for (y in 0 until RH) {
                val b = y * RW
                for (x in 0 until RW) {
                    val u = if (y > 0) B[b - RW + x] else 0f
                    val d = if (y < RH - 1) B[b + RW + x] else 0f
                    A[b + x] = 0.25f * u + 0.5f * B[b + x] + 0.25f * d
                }
            }
        }
        val F = A

        // 3) marching squares -> segmentos entre aristas
        val sa = IntBuf(4096); val sb = IntBuf(4096)
        for (y in 0 until RH - 1) {
            if ((y and 127) == 0 && cancelled()) throw CancelledException()
            for (x in 0 until RW - 1) {
                val i = y * RW + x
                val tl = F[i]; val tr = F[i + 1]; val bl = F[i + RW]; val br = F[i + RW + 1]
                var idx = 0
                if (tl >= 0.5f) idx = idx or 8
                if (tr >= 0.5f) idx = idx or 4
                if (br >= 0.5f) idx = idx or 2
                if (bl >= 0.5f) idx = idx or 1
                if (idx == 0 || idx == 15) continue
                val top = 2 * i; val bottom = 2 * (i + RW); val left = 2 * i + 1; val right = 2 * (i + 1) + 1
                when (idx) {
                    1, 14 -> { sa.add(left); sb.add(bottom) }
                    2, 13 -> { sa.add(bottom); sb.add(right) }
                    3, 12 -> { sa.add(left); sb.add(right) }
                    4, 11 -> { sa.add(top); sb.add(right) }
                    6, 9 -> { sa.add(top); sb.add(bottom) }
                    7, 8 -> { sa.add(top); sb.add(left) }
                    5 -> {
                        if ((tl + tr + bl + br) * 0.25f >= 0.5f) { sa.add(top); sb.add(left); sa.add(bottom); sb.add(right) }
                        else { sa.add(top); sb.add(right); sa.add(left); sb.add(bottom) }
                    }
                    10 -> {
                        if ((tl + tr + bl + br) * 0.25f >= 0.5f) { sa.add(top); sb.add(right); sa.add(left); sb.add(bottom) }
                        else { sa.add(top); sb.add(left); sa.add(bottom); sb.add(right) }
                    }
                }
            }
        }
        val S = sa.size
        if (S < 4) return emptyList()

        // 4) tabla arista -> segmentos (direccionamiento abierto)
        var cap = 1; while (cap < S * 4) cap = cap shl 1
        val mask = cap - 1
        val keys = IntArray(cap) { -1 }; val v1 = IntArray(cap) { -1 }; val v2 = IntArray(cap) { -1 }
        fun slot(k: Int): Int {
            var s = (k * -1640531527 ushr 7) and mask
            while (keys[s] != -1 && keys[s] != k) s = (s + 1) and mask
            return s
        }
        for (s in 0 until S) {
            for (e in intArrayOf(sa.a[s], sb.a[s])) {
                val sl = slot(e)
                if (keys[sl] == -1) keys[sl] = e
                if (v1[sl] == -1) v1[sl] = s else v2[sl] = s
            }
        }

        // 5) recorrer lazos, convertir a puntos y ajustar curvas
        val used = BooleanArray(S)
        val result = ArrayList<FloatArray>()
        val pts = IntBuf(4096)
        var loops = 0
        for (s in 0 until S) {
            if (used[s]) continue
            used[s] = true
            if ((++loops and 63) == 0 && cancelled()) throw CancelledException()
            pts.size = 0
            val startE = sa.a[s]
            var cur = sb.a[s]; var seg = s
            pts.add(startE)
            var ok = true
            while (cur != startE) {
                pts.add(cur)
                val sl = slot(cur)
                val o = if (v1[sl] == seg) v2[sl] else v1[sl]
                if (o < 0 || used[o]) { ok = false; break }
                used[o] = true
                cur = if (sa.a[o] == cur) sb.a[o] else sa.a[o]
                seg = o
            }
            if (!ok || pts.size < 4) continue

            val m = pts.size
            val xs = DoubleArray(m); val ys = DoubleArray(m)
            var n = 0
            for (q in 0 until m) {
                val id = pts.a[q]
                val e = id shr 1
                val ex = e % RW; val ey = e / RW
                var fx: Float; var fy: Float
                if ((id and 1) == 0) {
                    val f0 = F[e]; val f1 = F[e + 1]
                    fx = ex + (0.5f - f0) / (f1 - f0); fy = ey.toFloat()
                } else {
                    val f0 = F[e]; val f1 = F[e + RW]
                    fx = ex.toFloat(); fy = ey + (0.5f - f0) / (f1 - f0)
                }
                val px = (rx0 + fx + 0.5f).coerceIn(0f, w.toFloat()).toDouble()
                val py = (ry0 + fy + 0.5f).coerceIn(0f, h.toFloat()).toDouble()
                if (n > 0 && abs(px - xs[n - 1]) < 1e-3 && abs(py - ys[n - 1]) < 1e-3) continue
                xs[n] = px; ys[n] = py; n++
            }
            while (n > 1 && abs(xs[n - 1] - xs[0]) < 1e-3 && abs(ys[n - 1] - ys[0]) < 1e-3) n--
            if (n < 4) continue
            var area = 0.0
            for (q in 0 until n) { val r = (q + 1) % n; area += xs[q] * ys[r] - xs[r] * ys[q] }
            if (abs(area) * 0.5 < minArea) continue
            val c = Fitter(xs, ys, n, err2, win, cornerDeg).fitClosed()
            if (c != null && c.size >= 8) result.add(c)
        }
        return result
    }
}
