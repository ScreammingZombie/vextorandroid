package com.pvm.zombievectorizer.engine

import kotlin.math.abs

object Svg {
    private fun n(f: Float): String {
        val r = Math.round(f * 100f) / 100f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    fun write(v: VectorImage): String {
        val sb = StringBuilder(1 shl 16)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${v.width} ${v.height}\" ")
            .append("width=\"${v.width}\" height=\"${v.height}\"")
        if (v.transparent) sb.append(" data-zv-transparent=\"1\"")
        sb.append(">\n")
        for (l in v.layers) {
            sb.append("<path fill=\"#").append(String.format(java.util.Locale.US, "%06X", l.color and 0xFFFFFF))
                .append("\" fill-rule=\"evenodd\" d=\"")
            for (c in l.contours) {
                sb.append('M').append(n(c[0])).append(' ').append(n(c[1]))
                var px = c[0]; var py = c[1]
                var i = 2
                while (i + 5 < c.size) {
                    val x = c[i + 4]; val y = c[i + 5]
                    if (straight(px, py, c[i], c[i + 1], c[i + 2], c[i + 3], x, y)) {
                        sb.append('L').append(n(x)).append(' ').append(n(y))
                    } else {
                        sb.append('C').append(n(c[i])).append(' ').append(n(c[i + 1])).append(' ')
                            .append(n(c[i + 2])).append(' ').append(n(c[i + 3])).append(' ')
                            .append(n(x)).append(' ').append(n(y))
                    }
                    px = x; py = y
                    i += 6
                }
                sb.append('Z')
            }
            sb.append("\"/>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun straight(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Boolean {
        val dx = x3 - x0; val dy = y3 - y0
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1e-3f) return false
        val d1 = abs((x1 - x0) * dy - (y1 - y0) * dx) / len
        val d2 = abs((x2 - x0) * dy - (y2 - y0) * dx) / len
        return d1 < 0.02f && d2 < 0.02f
    }

    /** Lee un SVG generado por [write] (para el historial). */
    fun parse(text: String): VectorImage {
        val head = Regex("<svg[^>]*>").find(text)?.value ?: throw IllegalArgumentException("SVG inválido")
        val w = Regex("width=\"(\\d+)\"").find(head)!!.groupValues[1].toInt()
        val h = Regex("height=\"(\\d+)\"").find(head)!!.groupValues[1].toInt()
        val transparent = head.contains("data-zv-transparent")
        val layers = ArrayList<Layer>()
        val re = Regex("<path fill=\"#([0-9A-Fa-f]{6})\"[^>]*? d=\"([^\"]*)\"")
        for (m in re.findAll(text)) {
            val color = 0xFF000000.toInt() or m.groupValues[1].toInt(16)
            layers.add(Layer(color, parseD(m.groupValues[2])))
        }
        return VectorImage(w, h, layers, transparent)
    }

    private fun parseD(d: String): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val cur = ArrayList<Float>()
        var i = 0
        val len = d.length
        fun num(): Float {
            while (i < len && (d[i] == ' ' || d[i] == ',')) i++
            val s = i
            while (i < len && (d[i].isDigit() || d[i] == '.' || d[i] == '-' || d[i] == 'E' || d[i] == 'e')) i++
            return d.substring(s, i).toFloat()
        }
        var px = 0f; var py = 0f
        while (i < len) {
            when (d[i]) {
                'M' -> { i++; px = num(); py = num(); cur.clear(); cur.add(px); cur.add(py) }
                'L' -> {
                    i++; val x = num(); val y = num()
                    cur.add(px + (x - px) / 3); cur.add(py + (y - py) / 3)
                    cur.add(px + 2 * (x - px) / 3); cur.add(py + 2 * (y - py) / 3)
                    cur.add(x); cur.add(y); px = x; py = y
                }
                'C' -> {
                    i++
                    for (k in 0 until 6) cur.add(num())
                    px = cur[cur.size - 2]; py = cur[cur.size - 1]
                }
                'Z' -> { i++; if (cur.size >= 8) out.add(cur.toFloatArray()); cur.clear() }
                else -> i++
            }
        }
        return out
    }
}
