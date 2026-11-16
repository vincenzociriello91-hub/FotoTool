package it.vincenzo.fototool.motore

import kotlin.math.abs
import kotlin.math.pow

/** Colore: dalla fotocamera (bilanciata sul bianco) a Display P3 lineare, come vuole la specifica DNG. */
object Colore {
    /** Matrici del profilo colore del DNG (righe in fila, 9 valori). */
    class Profilo(
        val matrice1: DoubleArray?, val matrice2: DoubleArray?,
        val calibrazione1: DoubleArray?, val calibrazione2: DoubleArray?,
        val avanti1: DoubleArray?, val avanti2: DoubleArray?,
        val bilanciamentoAnalogico: DoubleArray?,
        val illuminante1: Int, val illuminante2: Int,
    )

    class Risultato(
        /** Da fotocamera divisa per il neutro a Display P3 lineare (bianco D65). */
        val matrice: Array<DoubleArray>,
        val temperatura: Double,
        val pesoIlluminante1: Double,
        val metodo: String,
    )

    // ---------- matrici 3x3 ----------
    fun m(v: DoubleArray) = Array(3) { r -> DoubleArray(3) { c -> v[r * 3 + c] } }
    fun id() = Array(3) { r -> DoubleArray(3) { c -> if (r == c) 1.0 else 0.0 } }
    fun diag(v: DoubleArray) = Array(3) { r -> DoubleArray(3) { c -> if (r == c) v[r] else 0.0 } }
    fun per(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { a[r][it] * b[it][c] } } }
    fun per(a: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { r -> (0..2).sumOf { a[r][it] * v[it] } }
    fun misto(a: Array<DoubleArray>, b: Array<DoubleArray>, t: Double) =
        Array(3) { r -> DoubleArray(3) { c -> a[r][c] * t + b[r][c] * (1 - t) } }
    fun inversa(a: Array<DoubleArray>): Array<DoubleArray> {
        val (x, y, z) = a
        val c00 = y[1] * z[2] - y[2] * z[1]
        val c01 = y[2] * z[0] - y[0] * z[2]
        val c02 = y[0] * z[1] - y[1] * z[0]
        val det = x[0] * c00 + x[1] * c01 + x[2] * c02
        require(abs(det) > 1e-12) { "matrice non invertibile" }
        return arrayOf(
            doubleArrayOf(c00 / det, (x[2] * z[1] - x[1] * z[2]) / det, (x[1] * y[2] - x[2] * y[1]) / det),
            doubleArrayOf(c01 / det, (x[0] * z[2] - x[2] * z[0]) / det, (x[2] * y[0] - x[0] * y[2]) / det),
            doubleArrayOf(c02 / det, (x[1] * z[0] - x[0] * z[1]) / det, (x[0] * y[1] - x[1] * y[0]) / det),
        )
    }

    // ---------- spazi colore ----------
    private val D50 = doubleArrayOf(0.96422, 1.0, 0.82521)
    private val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)
    private val BRADFORD = m(doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296))

    /** Adattamento cromatico di Bradford dal bianco [da] al bianco [a] (XYZ). */
    fun bradford(da: DoubleArray, a: DoubleArray): Array<DoubleArray> {
        val s = per(BRADFORD, da)
        val d = per(BRADFORD, a)
        return per(inversa(BRADFORD), per(diag(DoubleArray(3) { d[it] / s[it] }), BRADFORD))
    }

    /** Display P3 lineare (bianco D65) → XYZ D65, dai primari. */
    val P3_A_XYZ: Array<DoubleArray> by lazy {
        val prim = arrayOf(doubleArrayOf(0.680, 0.320), doubleArrayOf(0.265, 0.690), doubleArrayOf(0.150, 0.060))
        val p = Array(3) { r -> DoubleArray(3) { c -> val (x, y) = prim[c]; when (r) { 0 -> x / y; 1 -> 1.0; else -> (1 - x - y) / y } } }
        val s = per(inversa(p), D65)
        per(p, diag(s))
    }

    /** XYZ D50 → Display P3 lineare (D65). */
    val XYZ50_A_P3: Array<DoubleArray> by lazy { per(inversa(P3_A_XYZ), bradford(D50, D65)) }

    /** Coefficienti di luminanza di Display P3 (riga Y della matrice verso XYZ). */
    val LUMA: DoubleArray by lazy { P3_A_XYZ[1].copyOf() }

    // ---------- temperatura colore ----------
    fun temperaturaIlluminante(codice: Int): Double = when (codice) {
        3 -> 2850.0; 17 -> 2856.0; 24 -> 3200.0; 15 -> 3450.0; 2, 14 -> 4150.0; 18 -> 4874.0
        13 -> 5000.0; 23 -> 5003.0; 1, 4, 9 -> 5500.0; 20 -> 5503.0; 12 -> 6430.0; 10 -> 6500.0
        21 -> 6504.0; 19 -> 6774.0; 11 -> 7500.0; 22 -> 7504.0
        else -> 0.0
    }

    /** Temperatura correlata da xy (formula di McCamy). */
    fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449 * n.pow(3) + 3525 * n * n + 6823.3 * n + 5520.33
    }

    /**
     * Matrice finale per questo scatto: interpola le matrici dei due illuminanti secondo la temperatura
     * del bianco dello scatto (in mired, come il DNG SDK), poi porta in Display P3.
     */
    fun calcola(p: Profilo, neutro: DoubleArray): Risultato {
        val t1 = temperaturaIlluminante(p.illuminante1)
        val t2 = temperaturaIlluminante(p.illuminante2)
        val doppio = p.matrice1 != null && p.matrice2 != null && t1 > 0 && t2 > 0 && t1 != t2
        fun peso(t: Double) = if (!doppio) 1.0 else ((1 / t - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0)
        fun xyzACamera(g: Double): Array<DoubleArray> {
            val cm = if (doppio) misto(m(p.matrice1!!), m(p.matrice2!!), g) else m((p.matrice1 ?: p.matrice2)!!)
            val cc = when {
                doppio && p.calibrazione1 != null && p.calibrazione2 != null -> misto(m(p.calibrazione1), m(p.calibrazione2), g)
                p.calibrazione1 != null -> m(p.calibrazione1)
                else -> id()
            }
            val ab = p.bilanciamentoAnalogico?.let { diag(it) } ?: id()
            return per(ab, per(cc, cm))
        }
        // Itera: bianco xy → temperatura → matrice → nuovo bianco xy
        var g = 0.5
        var temp = 5000.0
        repeat(if (doppio) 6 else 1) {
            val xyz = per(inversa(xyzACamera(g)), neutro)
            val s = xyz.sum()
            temp = cct(xyz[0] / s, xyz[1] / s).coerceIn(1500.0, 50000.0)
            g = peso(temp)
        }
        val avanti = when {
            p.avanti1 != null && p.avanti2 != null && doppio -> misto(m(p.avanti1), m(p.avanti2), g)
            p.avanti1 != null -> m(p.avanti1)
            p.avanti2 != null -> m(p.avanti2)
            else -> null
        }
        val matrice = if (avanti != null) {
            // ForwardMatrix: dalla fotocamera bilanciata a XYZ D50, il bianco va già in D50
            per(XYZ50_A_P3, avanti)
        } else {
            val camAXyz = inversa(xyzACamera(g))
            val bianco = per(camAXyz, neutro)
            per(XYZ50_A_P3, per(bradford(bianco, D50), per(camAXyz, diag(neutro))))
        }
        return Risultato(matrice, temp, g, if (avanti != null) "ForwardMatrix" else "ColorMatrix")
    }

    /** Neutro della fotocamera per un bianco dato come xy (AsShotWhiteXY), con la matrice interpolata a quella temperatura. */
    fun neutroDaXY(p: Profilo, x: Double, y: Double): DoubleArray? {
        val m1 = p.matrice1 ?: p.matrice2 ?: return null
        val t1 = temperaturaIlluminante(p.illuminante1)
        val t2 = temperaturaIlluminante(p.illuminante2)
        val t = cct(x, y)
        val g = if (p.matrice1 != null && p.matrice2 != null && t1 > 0 && t2 > 0 && t1 != t2)
            ((1 / t - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0) else 1.0
        val cm = if (p.matrice2 != null && p.matrice1 != null) misto(m(p.matrice1), m(p.matrice2), g) else m(m1)
        val n = per(cm, doubleArrayOf(x / y, 1.0, (1 - x - y) / y))
        return DoubleArray(3) { n[it] / n[1] }
    }

    // ---------- curve di trasferimento ----------
    fun aSrgbEsatta(v: Float): Float = when {
        v <= 0f -> 0f
        v <= 0.0031308f -> v * 12.92f
        v >= 1f -> 1f
        else -> (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    // Indice sulla radice quadrata: la curva diventa quasi dritta e basta una tabella piccola (sta nella cache)
    private const val PASSI_A = 1024
    private val TAB_A = FloatArray(PASSI_A + 1) { val q = it.toFloat() / PASSI_A; aSrgbEsatta(q * q) }

    fun aSrgb(v: Float): Float {
        if (v <= 0f) return 0f
        if (v >= 1f) return 1f
        val f = kotlin.math.sqrt(v) * PASSI_A
        val i = f.toInt()
        return TAB_A[i] + (TAB_A[i + 1] - TAB_A[i]) * (f - i)
    }

    fun daSrgbEsatta(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055) / 1.055).pow(2.4).toFloat()

    // Tabelle con interpolazione lineare: errore sotto 1/10 di livello a 8 bit, molto più veloci di pow
    private const val PASSI = 4096
    private val TAB_DA = FloatArray(PASSI + 1) { daSrgbEsatta(it.toFloat() / PASSI) }

    fun daSrgb(v: Float): Float {
        if (v <= 0f) return v / 12.92f
        if (v >= 1f) return daSrgbEsatta(v)
        val f = v * PASSI
        val i = f.toInt()
        return TAB_DA[i] + (TAB_DA[i + 1] - TAB_DA[i]) * (f - i)
    }
}
