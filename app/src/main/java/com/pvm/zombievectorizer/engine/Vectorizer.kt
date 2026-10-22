package com.pvm.zombievectorizer.engine

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motor de vectorización 100% offline en Kotlin puro.
 *  - RÁPIDO: cuantización de color + trazado + curvas Bézier a resolución de trabajo normal.
 *  - MÁXIMA CALIDAD: antes agranda con interpolación bicúbica, limpia ruido/artefactos JPEG
 *    (filtro bilateral), usa más colores y ajusta las curvas con más precisión.
 */
object Vectorizer {
    const val PHOTO_SCORE = 4.8f

    // ------------------------------------------------------------ análisis
    fun analyze(px: IntArray, w: Int, h: Int): Analysis {
        val hist = Histogram(px, w, h)
        if (hist.opaque == 0L) return Analysis(false, max(w, h) < 700)
        val pal = Palette.build(hist, 12, 5f)
        val err = pal.meanError
        return Analysis(isPhoto = err > PHOTO_SCORE, isSmall = max(w, h) < 700, score = err)
    }

    // ------------------------------------------------------------ pipeline
    fun run(src: IntArray, sw: Int, sh: Int, mode: Mode, p: Progress): VectorImage {
        val max = mode == Mode.MAX
        val long = max(sw, sh).toFloat()
        val scale = if (max) {
            if (long < 1600f) min(3f, 1600f / long) else if (long > 2400f) 2400f / long else 1f
        } else {
            if (long > 2000f) 2000f / long else 1f
        }
        p.update("Preparando imagen", 0.02f)
        var w = sw; var h = sh
        var px = src
        if (abs(scale - 1f) > 0.01f) {
            w = max(1, Math.round(sw * scale)); h = max(1, Math.round(sh * scale))
            px = Resample.run(src, sw, sh, w, h)
        }
        if (p.cancelled()) throw CancelledException()
        if (max) {
            p.update("Limpiando ruido", 0.08f)
            px = Bilateral.run(px, w, h) { p.cancelled() }
        }

        p.update("Analizando colores", 0.15f)
        val hist = Histogram(px, w, h)
        val total = hist.opaque
        val transparent = total < w.toLong() * h
        if (total == 0L) return VectorImage(w, h, emptyList(), true)
        val pal = Palette.buildAuto(hist, if (max) 48 else 24, if (max) 4.2f else 7f, if (max) 2.2f else 3.5f, if (max) 0.02f else 0.03f)
        val n = pal.size

        // mapa de etiquetas ordenadas por área (0 = la más grande)
        val labels = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            labels[i] = if ((c ushr 24) < 128) 255.toByte() else pal.binLabel[Histogram.bin(c)]
        }
        // cajas por etiqueta
        val bx0 = IntArray(n) { Int.MAX_VALUE }; val by0 = IntArray(n) { Int.MAX_VALUE }
        val bx1 = IntArray(n) { -1 }; val by1 = IntArray(n) { -1 }
        for (y in 0 until h) for (x in 0 until w) {
            val l = labels[y * w + x].toInt() and 0xFF
            if (l < n) {
                if (x < bx0[l]) bx0[l] = x; if (x > bx1[l]) bx1[l] = x
                if (y < by0[l]) by0[l] = y; if (y > by1[l]) by1[l] = y
            }
        }

        val sc = max(1f, scale)
        val lowRes = max(w, h) < 900  // imágenes chicas: más suavizado para disimular los escalones de píxeles
        val passes = if (max) (if (scale > 1.5f) 2 else 1) else (if (lowRes) 3 else 1)
        val tol = if (max) 0.5 * (1 + (sc - 1) * 0.4) else (if (lowRes) 0.35 else 0.5)
        val win = 2.0 * (if (max) max(1.0, sc * 0.8) else 1.0)
        val minArea = 3.0 * sc * sc
        val cornerDeg = if (max) 45.0 else 50.0

        val margin = 3
        val bufA = FloatArray((w + 2 * margin + 2) * (h + 2 * margin + 2))
        val bufB = FloatArray(bufA.size)

        val layers = ArrayList<Layer>()
        for (j in 0 until n) {
            if (p.cancelled()) throw CancelledException()
            p.update("Trazando capa ${j + 1} de $n", 0.2f + 0.78f * j / n)
            val color = pal.colors[j]
            if (j == 0 && !transparent) {
                val W = w.toFloat(); val H = h.toFloat()
                layers.add(Layer(color, listOf(rect(W, H))))
                continue
            }
            // caja unión de las etiquetas >= j
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            for (l in j until n) {
                if (bx1[l] < 0) continue
                x0 = min(x0, bx0[l]); y0 = min(y0, by0[l]); x1 = max(x1, bx1[l]); y1 = max(y1, by1[l])
            }
            if (x1 < 0) continue
            val contours = Tracer.trace(labels, w, h, j, x0, y0, x1, y1, bufA, bufB, passes, tol * tol, win, cornerDeg, minArea) { p.cancelled() }
            if (contours.isNotEmpty()) layers.add(Layer(color, contours))
        }
        p.update("Listo", 1f)
        return VectorImage(w, h, layers, transparent)
    }

    private fun rect(w: Float, h: Float) = floatArrayOf(
        0f, 0f,
        0f, 0f, w, 0f, w, 0f,
        w, 0f, w, h, w, h,
        w, h, 0f, h, 0f, h,
        0f, h, 0f, 0f, 0f, 0f
    )
}

// ================================================================ histograma + paleta
internal class Histogram(px: IntArray, w: Int, h: Int) {
    val count = LongArray(32768)
    val sr = LongArray(32768); val sg = LongArray(32768); val sb = LongArray(32768)
    var opaque = 0L

    init {
        for (c in px) {
            if ((c ushr 24) < 128) continue
            val b = bin(c)
            count[b]++
            sr[b] += ((c shr 16) and 0xFF).toLong(); sg[b] += ((c shr 8) and 0xFF).toLong(); sb[b] += (c and 0xFF).toLong()
            opaque++
        }
    }

    companion object {
        fun bin(c: Int) = (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)
    }
}

internal class Palette(val size: Int, val colors: IntArray, val binLabel: ByteArray, val meanError: Float) {
    companion object {
        private val lin = FloatArray(256) {
            val c = it / 255f
            if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }

        fun distToSegment(px: Float, py: Float, pz: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
            val dx = bx - ax; val dy = by - ay; val dz = bz - az
            val l2 = dx * dx + dy * dy + dz * dz
            var t = if (l2 < 1e-9f) 0f else ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / l2
            t = t.coerceIn(0f, 1f)
            val ex = ax + t * dx - px; val ey = ay + t * dy - py; val ez = az + t * dz - pz
            return sqrt(ex * ex + ey * ey + ez * ez)
        }

        fun lab(r: Float, g: Float, b: Float, o: FloatArray) {
            val rl = lin[Math.round(r).coerceIn(0, 255)]; val gl = lin[Math.round(g).coerceIn(0, 255)]; val bl = lin[Math.round(b).coerceIn(0, 255)]
            val x = (0.4124f * rl + 0.3576f * gl + 0.1805f * bl) / 0.95047f
            val y = 0.2126f * rl + 0.7152f * gl + 0.0722f * bl
            val z = (0.0193f * rl + 0.1192f * gl + 0.9505f * bl) / 1.08883f
            fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
            val fx = f(x); val fy = f(y); val fz = f(z)
            o[0] = 116f * fy - 16f; o[1] = 500f * (fx - fy); o[2] = 200f * (fy - fz)
        }

        /** Prueba cantidades crecientes de colores y se queda con la primera que representa bien la imagen. */
        fun buildAuto(h: Histogram, kmax: Int, mergeDist: Float, target: Float, fringe: Float): Palette {
            var best: Palette? = null
            for (k in intArrayOf(3, 5, 8, 12, 18, 24, 32, 40, 48)) {
                if (k > kmax && best != null) break
                best = build(h, min(k, kmax), mergeDist, fringe)
                if (best.meanError <= target) break
            }
            return best!!
        }

        fun build(h: Histogram, kmax: Int, mergeDist: Float, fringe: Float = 0f): Palette {
            // bins presentes
            var m = 0
            for (i in 0 until 32768) if (h.count[i] > 0) m++
            val ids = IntArray(m); val L = FloatArray(m); val A = FloatArray(m); val B = FloatArray(m)
            val wt = FloatArray(m); val mr = FloatArray(m); val mg = FloatArray(m); val mb = FloatArray(m)
            var k = 0
            val tmp = FloatArray(3)
            for (i in 0 until 32768) {
                val c = h.count[i]
                if (c == 0L) continue
                ids[k] = i
                mr[k] = h.sr[i].toFloat() / c; mg[k] = h.sg[i].toFloat() / c; mb[k] = h.sb[i].toFloat() / c
                lab(mr[k], mg[k], mb[k], tmp)
                L[k] = tmp[0]; A[k] = tmp[1]; B[k] = tmp[2]; wt[k] = c.toFloat()
                k++
            }
            val kk = min(kmax, m)
            val cL = FloatArray(kk); val cA = FloatArray(kk); val cB = FloatArray(kk)
            // k-means++ ponderado
            val rnd = java.util.Random(7)
            var first = 0
            for (i in 0 until m) if (wt[i] > wt[first]) first = i
            cL[0] = L[first]; cA[0] = A[first]; cB[0] = B[first]
            val minD = FloatArray(m) { Float.MAX_VALUE }
            var cnt = 1
            while (cnt < kk) {
                var sum = 0.0
                for (i in 0 until m) {
                    val dl = L[i] - cL[cnt - 1]; val da = A[i] - cA[cnt - 1]; val db = B[i] - cB[cnt - 1]
                    val d = dl * dl + da * da + db * db
                    if (d < minD[i]) minD[i] = d
                    sum += minD[i].toDouble() * wt[i]
                }
                if (sum < 1e-6) break
                var r = rnd.nextDouble() * sum
                var pick = m - 1
                for (i in 0 until m) { r -= minD[i].toDouble() * wt[i]; if (r <= 0) { pick = i; break } }
                cL[cnt] = L[pick]; cA[cnt] = A[pick]; cB[cnt] = B[pick]; cnt++
            }
            var kc = cnt
            val assign = IntArray(m)
            val sL = DoubleArray(kc); val sA = DoubleArray(kc); val sB = DoubleArray(kc); val sW = DoubleArray(kc)
            for (iter in 0 until 12) {
                java.util.Arrays.fill(sL, 0.0); java.util.Arrays.fill(sA, 0.0); java.util.Arrays.fill(sB, 0.0); java.util.Arrays.fill(sW, 0.0)
                for (i in 0 until m) {
                    var best = 0; var bd = Float.MAX_VALUE
                    for (c in 0 until kc) {
                        val dl = L[i] - cL[c]; val da = A[i] - cA[c]; val db = B[i] - cB[c]
                        val d = dl * dl + da * da + db * db
                        if (d < bd) { bd = d; best = c }
                    }
                    assign[i] = best
                    sL[best] += L[i] * wt[i].toDouble(); sA[best] += A[i] * wt[i].toDouble(); sB[best] += B[i] * wt[i].toDouble(); sW[best] += wt[i].toDouble()
                }
                for (c in 0 until kc) if (sW[c] > 0) { cL[c] = (sL[c] / sW[c]).toFloat(); cA[c] = (sA[c] / sW[c]).toFloat(); cB[c] = (sB[c] / sW[c]).toFloat() }
            }
            // fusionar centros casi iguales
            val alive = BooleanArray(kc) { true }
            val cw = DoubleArray(kc) { sW[it] }
            var merged = true
            while (merged) {
                merged = false
                var bi = -1; var bj = -1; var bd = mergeDist * mergeDist
                for (a in 0 until kc) if (alive[a]) for (b in a + 1 until kc) if (alive[b]) {
                    val dl = cL[a] - cL[b]; val da = cA[a] - cA[b]; val db = cB[a] - cB[b]
                    val d = dl * dl + da * da + db * db
                    if (d < bd) { bd = d; bi = a; bj = b }
                }
                if (bi >= 0) {
                    val wa = cw[bi]; val wb = cw[bj]; val ws = max(1e-9, wa + wb)
                    cL[bi] = ((cL[bi] * wa + cL[bj] * wb) / ws).toFloat()
                    cA[bi] = ((cA[bi] * wa + cA[bj] * wb) / ws).toFloat()
                    cB[bi] = ((cB[bi] * wa + cB[bj] * wb) / ws).toFloat()
                    cw[bi] = ws; alive[bj] = false; merged = true
                }
            }
            val keepL = (0 until kc).filter { alive[it] && cw[it] >= h.opaque * 0.0003 }.ifEmpty { listOf((0 until kc).maxByOrNull { cw[it] }!!) }.toMutableList()
            // quitar colores "de transición" (bordes suavizados): chicos y ubicados entre otros dos colores
            if (fringe > 0f) {
                var changed = true
                while (changed && keepL.size > 2) {
                    changed = false
                    for (c in keepL.sortedBy { cw[it] }) {
                        if (cw[c] > h.opaque * fringe) break
                        var between = false
                        for (a in keepL) { if (a == c || cw[a] <= cw[c]) continue
                            for (b in keepL) { if (b <= a || b == c || cw[b] <= cw[c]) continue
                                if (distToSegment(cL[c], cA[c], cB[c], cL[a], cA[a], cB[a], cL[b], cA[b], cB[b]) < 3.5f) { between = true; break }
                            }
                            if (between) break
                        }
                        if (between) { keepL.remove(c); changed = true; break }
                    }
                }
            }
            val keep: List<Int> = keepL
            // asignación final y colores medios
            val ks = keep.size
            val area = DoubleArray(ks); val ar = DoubleArray(ks); val ag = DoubleArray(ks); val ab = DoubleArray(ks)
            val finalAssign = IntArray(m)
            var errSum = 0.0
            for (i in 0 until m) {
                var best = 0; var bd = Float.MAX_VALUE
                for (t in 0 until ks) {
                    val c = keep[t]
                    val dl = L[i] - cL[c]; val da = A[i] - cA[c]; val db = B[i] - cB[c]
                    val d = dl * dl + da * da + db * db
                    if (d < bd) { bd = d; best = t }
                }
                finalAssign[i] = best
                area[best] += wt[i].toDouble()
                ar[best] += mr[i] * wt[i].toDouble(); ag[best] += mg[i] * wt[i].toDouble(); ab[best] += mb[i] * wt[i].toDouble()
                errSum += sqrt(bd.toDouble()) * wt[i]
            }
            val order = (0 until ks).sortedByDescending { area[it] }
            val rank = IntArray(ks); for ((r, t) in order.withIndex()) rank[t] = r
            val colors = IntArray(ks)
            for (t in 0 until ks) {
                val a = max(1.0, area[t])
                colors[rank[t]] = (0xFF shl 24) or (Math.round(ar[t] / a).toInt().coerceIn(0, 255) shl 16) or
                        (Math.round(ag[t] / a).toInt().coerceIn(0, 255) shl 8) or Math.round(ab[t] / a).toInt().coerceIn(0, 255)
            }
            val binLabel = ByteArray(32768)
            for (i in 0 until m) binLabel[ids[i]] = rank[finalAssign[i]].toByte()
            return Palette(ks, colors, binLabel, (errSum / max(1L, h.opaque)).toFloat())
        }
    }
}

// ================================================================ remuestreo bicúbico (Catmull-Rom)
internal object Resample {
    private fun cr(x: Float): Float {
        val a = abs(x)
        return if (a < 1f) 1.5f * a * a * a - 2.5f * a * a + 1f
        else if (a < 2f) -0.5f * a * a * a + 2.5f * a * a - 4f * a + 2f else 0f
    }

    private class Axis(val first: IntArray, val cnt: IntArray, val w: Array<FloatArray>)

    private fun axis(n: Int, nn: Int): Axis {
        val scale = nn.toFloat() / n
        val fs = max(1f, 1f / scale)
        val sup = 2f * fs
        val first = IntArray(nn); val cnt = IntArray(nn); val ws = Array(nn) { FloatArray(0) }
        for (i in 0 until nn) {
            val c = (i + 0.5f) / scale - 0.5f
            val lo = ceil(c - sup).toInt(); val hi = floor(c + sup).toInt()
            val arr = FloatArray(hi - lo + 1)
            var s = 0f
            for (j in lo..hi) { val v = cr((j - c) / fs); arr[j - lo] = v; s += v }
            for (j in arr.indices) arr[j] /= s
            first[i] = lo; cnt[i] = arr.size; ws[i] = arr
        }
        return Axis(first, cnt, ws)
    }

    fun run(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val ax = axis(w, nw); val ay = axis(h, nh)
        // paso horizontal -> planos float (R*a, G*a, B*a, a)
        val tr = FloatArray(h * nw); val tg = FloatArray(h * nw); val tb = FloatArray(h * nw); val ta = FloatArray(h * nw)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until nw) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                val f = ax.first[x]; val ww = ax.w[x]
                for (k in ww.indices) {
                    val sx = (f + k).coerceIn(0, w - 1)
                    val c = src[row + sx]
                    val al = (c ushr 24) / 255f
                    val wk = ww[k]
                    r += ((c shr 16) and 0xFF) * al * wk; g += ((c shr 8) and 0xFF) * al * wk
                    b += (c and 0xFF) * al * wk; a += al * wk
                }
                val o = y * nw + x
                tr[o] = r; tg[o] = g; tb[o] = b; ta[o] = a
            }
        }
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val f = ay.first[y]; val ww = ay.w[y]
            for (x in 0 until nw) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (k in ww.indices) {
                    val sy = (f + k).coerceIn(0, h - 1)
                    val o = sy * nw + x; val wk = ww[k]
                    r += tr[o] * wk; g += tg[o] * wk; b += tb[o] * wk; a += ta[o] * wk
                }
                val al = a.coerceIn(0f, 1f)
                if (al < 0.002f) { out[y * nw + x] = 0; continue }
                val ri = Math.round(r / a).coerceIn(0, 255); val gi = Math.round(g / a).coerceIn(0, 255); val bi = Math.round(b / a).coerceIn(0, 255)
                out[y * nw + x] = (Math.round(al * 255f) shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return out
    }
}

// ================================================================ filtro bilateral (limpia ruido y artefactos JPEG)
internal object Bilateral {
    fun run(src: IntArray, w: Int, h: Int, cancelled: () -> Boolean): IntArray {
        val rad = 2
        val sp = FloatArray(25)
        for (dy in -rad..rad) for (dx in -rad..rad) sp[(dy + rad) * 5 + dx + rad] = exp(-(dx * dx + dy * dy) / (2f * 1.3f * 1.3f))
        val rng = FloatArray(766) { val d = it / 3f; exp(-(d * d) / (2f * 26f * 26f)) }
        val out = IntArray(src.size)
        for (y in 0 until h) {
            if ((y and 63) == 0 && cancelled()) throw CancelledException()
            for (x in 0 until w) {
                val c = src[y * w + x]
                val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (dy in -rad..rad) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -rad..rad) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val q = src[yy * w + xx]
                        val qr = (q shr 16) and 0xFF; val qg = (q shr 8) and 0xFF; val qb = q and 0xFF
                        val wgt = sp[(dy + rad) * 5 + dx + rad] * rng[abs(qr - cr) + abs(qg - cg) + abs(qb - cb)]
                        sr += qr * wgt; sg += qg * wgt; sb += qb * wgt; sw += wgt
                    }
                }
                out[y * w + x] = (c and 0xFF000000.toInt()) or (Math.round(sr / sw) shl 16) or (Math.round(sg / sw) shl 8) or Math.round(sb / sw)
            }
        }
        return out
    }
}
