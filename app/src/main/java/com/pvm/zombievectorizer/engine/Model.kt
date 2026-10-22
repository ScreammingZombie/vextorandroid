package com.pvm.zombievectorizer.engine

/** Un contorno cerrado: [x0, y0, (c1x, c1y, c2x, c2y, x, y) * N]. Todos los tramos son cúbicas. */
class Layer(val color: Int, val contours: List<FloatArray>)

/** Resultado de vectorizar: capas apiladas (la primera va al fondo), relleno even-odd. */
class VectorImage(val width: Int, val height: Int, val layers: List<Layer>, val transparent: Boolean)

enum class Mode { FAST, MAX }

class Analysis(val isPhoto: Boolean, val isSmall: Boolean, val score: Float = 0f) {
    val recommended: Mode get() = if (isPhoto || isSmall) Mode.MAX else Mode.FAST
}

interface Progress {
    fun update(stage: String, fraction: Float)
    fun cancelled(): Boolean
}

class CancelledException : RuntimeException("cancelado")
