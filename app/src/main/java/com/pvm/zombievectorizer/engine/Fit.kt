package com.pvm.zombievectorizer.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

internal class FloatBuf(cap: Int = 256) {
    var a = FloatArray(cap)
    var size = 0
    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    fun toArray(): FloatArray = a.copyOf(size)
}

/** Ajuste de curvas Bézier cúbicas (algoritmo de Schneider) con detección de esquinas. */
internal class Fitter(private val x: DoubleArray, private val y: DoubleArray, private val n: Int,
                      private val err2: Double, private val cornerWin: Double, private val cornerDeg: Double) {

    private val out = FloatBuf(512)

    fun fitClosed(): FloatArray? {
        if (n < 4) return null
        val corners = findCorners()
        val idx = ArrayList<Int>()
        for (i in 0 until n) if (corners[i]) idx.add(i)
        if (idx.isEmpty()) {
            // curva suave cerrada: se parte en dos mitades con tangentes continuas
            val m = n / 2
            val d0 = dir(n - 1, 1)
            val dm = dir(m - 1, m + 1)
            out.add(x[0].toFloat()); out.add(y[0].toFloat())
            fitRun(0, m, d0[0], d0[1], -dm[0], -dm[1])
            fitRun(m, n, dm[0], dm[1], -d0[0], -d0[1])
        } else {
            out.add(x[idx[0]].toFloat()); out.add(y[idx[0]].toFloat())
            for (c in idx.indices) {
                val s = idx[c]
                var e = idx[(c + 1) % idx.size]
                if (e <= s) e += n
                if (idx.size == 1) {
                    // una sola esquina: se parte por la mitad
                    val m = s + n / 2
                    val tOut = tangentForward(s); val tIn = tangentBackward(s + n)
                    val dm = dir((m - 1) % n, (m + 1) % n)
                    fitRun(s, m, tOut[0], tOut[1], -dm[0], -dm[1])
                    fitRun(m, s + n, dm[0], dm[1], tIn[0], tIn[1])
                } else {
                    val tOut = tangentForward(s); val tIn = tangentBackward(e)
                    fitRun(s, e, tOut[0], tOut[1], tIn[0], tIn[1])
                }
            }
        }
        return out.toArray()
    }

    // ---- utilidades de índices cíclicos ----
    private fun px(i: Int) = x[((i % n) + n) % n]
    private fun py(i: Int) = y[((i % n) + n) % n]

    private fun dir(a: Int, b: Int): DoubleArray {
        val dx = px(b) - px(a); val dy = py(b) - py(a)
        val l = hypot(dx, dy)
        return if (l < 1e-9) doubleArrayOf(1.0, 0.0) else doubleArrayOf(dx / l, dy / l)
    }

    private fun tangentForward(i: Int): DoubleArray {
        var j = i; var acc = 0.0; var steps = 0
        while (acc < 1.6 && steps < n / 2) { acc += hypot(px(j + 1) - px(j), py(j + 1) - py(j)); j++; steps++ }
        return dir(i, j)
    }

    private fun tangentBackward(i: Int): DoubleArray {
        var j = i; var acc = 0.0; var steps = 0
        while (acc < 1.6 && steps < n / 2) { acc += hypot(px(j - 1) - px(j), py(j - 1) - py(j)); j--; steps++ }
        return dir(i, j)
    }

    private fun findCorners(): BooleanArray {
        val ang = DoubleArray(n)
        val lo = IntArray(n); val hi = IntArray(n)
        for (i in 0 until n) {
            var j = i; var acc = 0.0; var st = 0
            while (acc < cornerWin && st < n / 2) { acc += hypot(px(j) - px(j - 1), py(j) - py(j - 1)); j--; st++ }
            var k = i; acc = 0.0; st = 0
            while (acc < cornerWin && st < n / 2) { acc += hypot(px(k + 1) - px(k), py(k + 1) - py(k)); k++; st++ }
            lo[i] = j; hi[i] = k
            val v1x = px(i) - px(j); val v1y = py(i) - py(j)
            val v2x = px(k) - px(i); val v2y = py(k) - py(i)
            val l = hypot(v1x, v1y) * hypot(v2x, v2y)
            ang[i] = if (l < 1e-9) 0.0 else Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / l).coerceIn(-1.0, 1.0)))
        }
        val res = BooleanArray(n)
        for (i in 0 until n) {
            if (ang[i] < cornerDeg) continue
            var best = true
            for (t in lo[i] until i) if (ang[((t % n) + n) % n] >= ang[i]) { best = false; break }
            if (best) for (t in i + 1..hi[i]) if (ang[((t % n) + n) % n] > ang[i]) { best = false; break }
            res[i] = best
        }
        return res
    }

    // ---- Schneider ----
    /** Ajusta los puntos first..last (índices cíclicos, last puede ser >= n). */
    private fun fitRun(first: Int, last: Int, t1x: Double, t1y: Double, t2x: Double, t2y: Double) {
        val cnt = last - first + 1
        if (cnt < 2) return
        val xs = DoubleArray(cnt) { px(first + it) }
        val ys = DoubleArray(cnt) { py(first + it) }
        fitCubic(xs, ys, 0, cnt - 1, t1x, t1y, t2x, t2y)
    }

    private fun emit(b: DoubleArray) {
        out.add(b[2].toFloat()); out.add(b[3].toFloat())
        out.add(b[4].toFloat()); out.add(b[5].toFloat())
        out.add(b[6].toFloat()); out.add(b[7].toFloat())
    }

    private fun fitCubic(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int,
                         t1x: Double, t1y: Double, t2x: Double, t2y: Double) {
        val cnt = last - first + 1
        if (cnt == 2) {
            val d = hypot(xs[last] - xs[first], ys[last] - ys[first]) / 3.0
            emit(doubleArrayOf(xs[first], ys[first], xs[first] + t1x * d, ys[first] + t1y * d,
                xs[last] + t2x * d, ys[last] + t2y * d, xs[last], ys[last]))
            return
        }
        var u = chord(xs, ys, first, last)
        var bez = generate(xs, ys, first, last, u, t1x, t1y, t2x, t2y)
        var split = IntArray(1)
        var maxErr = maxError(xs, ys, first, last, bez, u, split)
        if (maxErr < err2) { emit(bez); return }
        if (maxErr < err2 * 16) {
            for (it in 0 until 4) {
                u = reparam(xs, ys, first, last, u, bez)
                bez = generate(xs, ys, first, last, u, t1x, t1y, t2x, t2y)
                maxErr = maxError(xs, ys, first, last, bez, u, split)
                if (maxErr < err2) { emit(bez); return }
            }
        }
        var sp = split[0]
        if (sp <= first) sp = first + 1
        if (sp >= last) sp = last - 1
        val cx = xs[sp - 1] - xs[sp + 1]; val cy = ys[sp - 1] - ys[sp + 1]
        val l = hypot(cx, cy)
        val tcx = if (l < 1e-9) 1.0 else cx / l
        val tcy = if (l < 1e-9) 0.0 else cy / l
        fitCubic(xs, ys, first, sp, t1x, t1y, tcx, tcy)
        fitCubic(xs, ys, sp, last, -tcx, -tcy, t2x, t2y)
    }

    private fun chord(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int): DoubleArray {
        val u = DoubleArray(last - first + 1)
        for (i in first + 1..last) u[i - first] = u[i - first - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val tot = u[last - first]
        if (tot < 1e-12) for (i in u.indices) u[i] = i.toDouble() / (u.size - 1)
        else for (i in u.indices) u[i] /= tot
        return u
    }

    private fun generate(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, u: DoubleArray,
                         t1x: Double, t1y: Double, t2x: Double, t2y: Double): DoubleArray {
        val p0x = xs[first]; val p0y = ys[first]; val p3x = xs[last]; val p3y = ys[last]
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        for (i in 0..last - first) {
            val t = u[i]; val s = 1 - t
            val b0 = s * s * s; val b1 = 3 * s * s * t; val b2 = 3 * s * t * t; val b3 = t * t * t
            val a1x = t1x * b1; val a1y = t1y * b1; val a2x = t2x * b2; val a2y = t2y * b2
            c00 += a1x * a1x + a1y * a1y
            c01 += a1x * a2x + a1y * a2y
            c11 += a2x * a2x + a2y * a2y
            val tx = xs[first + i] - (p0x * (b0 + b1) + p3x * (b2 + b3))
            val ty = ys[first + i] - (p0y * (b0 + b1) + p3y * (b2 + b3))
            x0 += a1x * tx + a1y * ty
            x1 += a2x * tx + a2y * ty
        }
        val det = c00 * c11 - c01 * c01
        var aL = 0.0; var aR = 0.0
        if (abs(det) > 1e-12) { aL = (x0 * c11 - x1 * c01) / det; aR = (c00 * x1 - c01 * x0) / det }
        val seg = hypot(p3x - p0x, p3y - p0y)
        val eps = 1e-6 * seg
        if (aL < eps || aR < eps || aL > seg * 2.5 || aR > seg * 2.5) { aL = seg / 3; aR = seg / 3 }
        return doubleArrayOf(p0x, p0y, p0x + t1x * aL, p0y + t1y * aL, p3x + t2x * aR, p3y + t2y * aR, p3x, p3y)
    }

    private fun bez(b: DoubleArray, t: Double, o: DoubleArray) {
        val s = 1 - t
        val b0 = s * s * s; val b1 = 3 * s * s * t; val b2 = 3 * s * t * t; val b3 = t * t * t
        o[0] = b[0] * b0 + b[2] * b1 + b[4] * b2 + b[6] * b3
        o[1] = b[1] * b0 + b[3] * b1 + b[5] * b2 + b[7] * b3
    }

    private fun maxError(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, b: DoubleArray,
                         u: DoubleArray, split: IntArray): Double {
        var worst = 0.0
        split[0] = (first + last) / 2
        val p = DoubleArray(2)
        for (i in first + 1 until last) {
            bez(b, u[i - first], p)
            val dx = p[0] - xs[i]; val dy = p[1] - ys[i]
            val d = dx * dx + dy * dy
            if (d >= worst) { worst = d; split[0] = i }
        }
        return worst
    }

    private fun reparam(xs: DoubleArray, ys: DoubleArray, first: Int, last: Int, u: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(u.size)
        val p = DoubleArray(2)
        for (i in u.indices) {
            val t = u[i]
            bez(b, t, p)
            val s = 1 - t
            // primera y segunda derivada
            val d1x = 3 * (s * s * (b[2] - b[0]) + 2 * s * t * (b[4] - b[2]) + t * t * (b[6] - b[4]))
            val d1y = 3 * (s * s * (b[3] - b[1]) + 2 * s * t * (b[5] - b[3]) + t * t * (b[7] - b[5]))
            val d2x = 6 * (s * (b[4] - 2 * b[2] + b[0]) + t * (b[6] - 2 * b[4] + b[2]))
            val d2y = 6 * (s * (b[5] - 2 * b[3] + b[1]) + t * (b[7] - 2 * b[5] + b[3]))
            val dx = p[0] - xs[first + i]; val dy = p[1] - ys[first + i]
            val num = dx * d1x + dy * d1y
            val den = d1x * d1x + d1y * d1y + dx * d2x + dy * d2y
            r[i] = if (abs(den) < 1e-12) t else (t - num / den).coerceIn(0.0, 1.0)
        }
        // mantener monotonía
        for (i in 1 until r.size) if (r[i] < r[i - 1]) r[i] = r[i - 1]
        return r
    }
}
