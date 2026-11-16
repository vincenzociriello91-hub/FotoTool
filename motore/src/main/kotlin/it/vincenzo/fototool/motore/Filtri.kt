package it.vincenzo.fototool.motore

import java.util.stream.IntStream

/** Filtri su immagini float in fila (riga dopo riga), in parallelo su tutti i processori. */
object Filtri {
    inline fun perRighe(n: Int, crossinline f: (Int) -> Unit) {
        IntStream.range(0, n).parallel().forEach { f(it) }
    }

    /** Media su una finestra (2r+1)x(2r+1), con i bordi normalizzati sui pixel che esistono davvero. */
    fun media(src: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray, dst: FloatArray) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, w * h); return }
        perRighe(h) { y ->
            val o = y * w
            var s = 0f
            for (x in 0..minOf(r, w - 1)) s += src[o + x]
            for (x in 0 until w) {
                val a = x - r
                val b = x + r
                tmp[o + x] = s / (minOf(b, w - 1) - maxOf(a, 0) + 1)
                if (b + 1 < w) s += src[o + b + 1]
                if (a >= 0) s -= src[o + a]
            }
        }
        val blocco = 256
        perRighe((w + blocco - 1) / blocco) { bx ->
            val x0 = bx * blocco
            val x1 = minOf(w, x0 + blocco)
            val acc = FloatArray(x1 - x0)
            for (y in 0..minOf(r, h - 1)) { val o = y * w; for (x in x0 until x1) acc[x - x0] += tmp[o + x] }
            for (y in 0 until h) {
                val a = y - r
                val b = y + r
                val inv = 1f / (minOf(b, h - 1) - maxOf(a, 0) + 1)
                val o = y * w
                for (x in x0 until x1) dst[o + x] = acc[x - x0] * inv
                if (b + 1 < h) { val ob = (b + 1) * w; for (x in x0 until x1) acc[x - x0] += tmp[ob + x] }
                if (a >= 0) { val oa = a * w; for (x in x0 until x1) acc[x - x0] -= tmp[oa + x] }
            }
        }
    }

    /**
     * Filtro guidato (He et al.): leviga [p] seguendo i bordi della guida [g].
     * Con g = p è un levigatore che conserva i bordi. Il risultato va in [out] (può essere p o g).
     */
    fun guidato(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float, out: FloatArray) {
        val (ma, mb) = coefficienti(g, p, w, h, r, eps)
        perRighe(h) { y -> val o = y * w; for (x in o until o + w) out[x] = ma[x] * g[x] + mb[x] }
    }

    /**
     * Coefficienti medi (a, b) del filtro guidato: il risultato è a·guida + b.
     * Calcolati a bassa risoluzione e ingranditi, danno il "filtro guidato veloce": bordi netti a piena risoluzione.
     */
    fun coefficienti(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): Pair<FloatArray, FloatArray> {
        val n = w * h
        val tmp = FloatArray(n)
        val mg = FloatArray(n); media(g, w, h, r, tmp, mg)
        val mp = FloatArray(n); media(p, w, h, r, tmp, mp)
        val prod = FloatArray(n)
        perRighe(h) { y -> val o = y * w; for (x in o until o + w) prod[x] = g[x] * p[x] }
        val mgp = FloatArray(n); media(prod, w, h, r, tmp, mgp)
        val mgg: FloatArray
        if (g === p) mgg = mgp else {
            perRighe(h) { y -> val o = y * w; for (x in o until o + w) prod[x] = g[x] * g[x] }
            mgg = FloatArray(n); media(prod, w, h, r, tmp, mgg)
        }
        // a e b riusano gli array delle medie dei prodotti
        val a = mgp
        val b = prod
        perRighe(h) { y ->
            val o = y * w
            for (x in o until o + w) {
                val v = mgg[x] - mg[x] * mg[x]
                val av = (a[x] - mg[x] * mp[x]) / (v + eps)
                a[x] = av
                b[x] = mp[x] - av * mg[x]
            }
        }
        media(a, w, h, r, tmp, mg)
        media(b, w, h, r, tmp, mp)
        return mg to mp
    }

    /**
     * Ingrandimento bilineare veloce di una mappa piccola (mw x mh) su una riga di [w] pixel:
     * la parte orizzontale (indici e pesi) si calcola una volta, quella verticale una volta per riga.
     * Stesso risultato di [bilineare] con fx = x·scala + spost.
     */
    class Ingrandimento(private val m: FloatArray, private val mw: Int, private val mh: Int, w: Int, scala: Float, spost: Float) {
        private val x0 = IntArray(w)
        private val tx = FloatArray(w)
        init {
            for (x in 0 until w) {
                val f = (x * scala + spost).coerceIn(0f, (mw - 1).toFloat())
                val i = f.toInt().coerceAtMost(mw - 1)
                x0[x] = i; tx[x] = f - i
            }
        }

        /** Riempie [out] (w valori) con la riga di mappa alla coordinata verticale [fy]; [tmp] è lungo mw. */
        fun riga(fy: Float, tmp: FloatArray, out: FloatArray) {
            val y = fy.coerceIn(0f, (mh - 1).toFloat())
            val y0 = y.toInt().coerceAtMost(mh - 1)
            val y1 = minOf(y0 + 1, mh - 1)
            val ty = y - y0
            val a = y0 * mw; val b = y1 * mw
            for (i in 0 until mw) tmp[i] = m[a + i] + (m[b + i] - m[a + i]) * ty
            for (x in out.indices) {
                val i = x0[x]
                val j = if (i + 1 < mw) i + 1 else i
                out[x] = tmp[i] + (tmp[j] - tmp[i]) * tx[x]
            }
        }
    }

    /** Campionamento bilineare di una mappa (mw x mh) in coordinate di mappa frazionarie. */
    fun bilineare(m: FloatArray, mw: Int, mh: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (mw - 1).toFloat())
        val y = fy.coerceIn(0f, (mh - 1).toFloat())
        val x0 = x.toInt().coerceAtMost(mw - 1)
        val y0 = y.toInt().coerceAtMost(mh - 1)
        val x1 = minOf(x0 + 1, mw - 1)
        val y1 = minOf(y0 + 1, mh - 1)
        val tx = x - x0
        val ty = y - y0
        val a = m[y0 * mw + x0]
        val b = m[y0 * mw + x1]
        val c = m[y1 * mw + x0]
        val d = m[y1 * mw + x1]
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }
}
