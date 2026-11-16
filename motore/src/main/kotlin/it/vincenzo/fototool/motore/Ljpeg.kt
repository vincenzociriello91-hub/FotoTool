package it.vincenzo.fototool.motore

/**
 * Decodificatore JPEG senza perdita (ITU T.81, SOF3), quello usato dai DNG compressi (Compression = 7).
 * Restituisce i campioni in ordine di riga, componenti interlacciate: è così che il DNG li dispone nella tessera.
 */
object Ljpeg {
    class Fotogramma(val larghezza: Int, val altezza: Int, val componenti: Int, val campioni: IntArray)

    fun decodifica(d: ByteArray, inizio: Int = 0, fine: Int = d.size): Fotogramma {
        fun u8(i: Int) = d[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        var p = inizio
        require(u8(p) == 0xFF && u8(p + 1) == 0xD8) { "manca l'inizio del JPEG senza perdita" }
        p += 2
        val tabelle = arrayOfNulls<IntArray>(4)
        var precisione = 0
        var larg = 0
        var alt = 0
        var nf = 0
        var ids = IntArray(0)
        var intervallo = 0
        while (p < fine - 1) {
            if (u8(p) != 0xFF) { p++; continue }
            val m = u8(p + 1)
            p += 2
            if (m == 0xFF || m == 0x00 || m == 0xD8 || m in 0xD0..0xD7) { if (m == 0xFF) p--; continue }
            if (m == 0xD9) break
            val len = u16(p)
            when (m) {
                0xC4 -> {
                    var q = p + 2
                    while (q < p + len) {
                        val th = u8(q) and 0x0F
                        val conta = IntArray(17) { if (it == 0) 0 else u8(q + it) }
                        val totale = conta.sum()
                        val simboli = IntArray(totale) { u8(q + 17 + it) }
                        tabelle[th] = tabella(conta, simboli)
                        q += 17 + totale
                    }
                }
                0xC3 -> {
                    precisione = u8(p + 2)
                    alt = u16(p + 3)
                    larg = u16(p + 5)
                    nf = u8(p + 7)
                    ids = IntArray(nf) { u8(p + 8 + 3 * it) }
                }
                0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                    error("JPEG non senza perdita (marcatore ${m.toString(16)})")
                0xDD -> intervallo = u16(p + 2)
                0xDA -> {
                    val ns = u8(p + 2)
                    require(ns == nf) { "scansione con $ns componenti su $nf" }
                    val tab = Array(ns) { j ->
                        val cs = u8(p + 3 + 2 * j)
                        val td = u8(p + 4 + 2 * j) ushr 4
                        require(cs in ids) { "componente $cs sconosciuta" }
                        tabelle[td] ?: error("tabella di Huffman $td mancante")
                    }
                    val predittore = u8(p + 3 + 2 * ns)
                    val pt = u8(p + 5 + 2 * ns) and 0x0F
                    return scansione(d, p + len, fine, larg, alt, nf, tab, predittore, precisione, pt, intervallo)
                }
            }
            p += len
        }
        error("nessuna scansione trovata nel JPEG senza perdita")
    }

    /** Tabella di ricerca a 16 bit: (lunghezza codice shl 8) or simbolo; 0 = codice non valido. */
    private fun tabella(conta: IntArray, simboli: IntArray): IntArray {
        val t = IntArray(65536)
        var codice = 0
        var k = 0
        for (len in 1..16) {
            repeat(conta[len]) {
                val s = simboli[k++]
                val base = codice shl (16 - len)
                val n = 1 shl (16 - len)
                for (j in 0 until n) t[base + j] = (len shl 8) or s
                codice++
            }
            codice = codice shl 1
        }
        return t
    }

    private fun scansione(
        d: ByteArray, inizio: Int, fine: Int, x: Int, y: Int, nf: Int, tab: Array<IntArray>,
        predittore: Int, precisione: Int, pt: Int, intervallo: Int,
    ): Fotogramma {
        val out = IntArray(x * y * nf)
        val iniziale = 1 shl (precisione - pt - 1)
        var p = inizio
        var acc = 0L
        var bit = 0
        fun riempi() {
            while (bit <= 48) {
                var v = 0
                if (p < fine) {
                    v = d[p].toInt() and 0xFF
                    if (v == 0xFF) {
                        val dopo = if (p + 1 < fine) d[p + 1].toInt() and 0xFF else 0xD9
                        if (dopo == 0x00) p += 2 else v = 0 // marcatore: non lo supero, aggiungo zeri
                    } else p++
                }
                acc = (acc shl 8) or v.toLong()
                bit += 8
            }
        }
        val riga = x * nf
        var primaRiga = 0
        var mcu = 0
        for (r in 0 until y) {
            if (intervallo > 0 && mcu > 0 && mcu % intervallo == 0) {
                // Riavvio: si scartano i bit rimasti, si salta il marcatore RSTn e la predizione riparte.
                acc = 0; bit = 0
                while (p < fine - 1 && !((d[p].toInt() and 0xFF) == 0xFF && (d[p + 1].toInt() and 0xFF) in 0xD0..0xD7)) p++
                p += 2
                primaRiga = r
            }
            for (c in 0 until x) {
                for (k in 0 until nf) {
                    if (bit < 32) riempi()
                    val e = tab[k][((acc ushr (bit - 16)) and 0xFFFF).toInt()]
                    val len = e ushr 8
                    if (len == 0) error("codice di Huffman non valido (riga $r, colonna $c)")
                    bit -= len
                    val s = e and 0xFF
                    val diff = when (s) {
                        0 -> 0
                        16 -> 32768
                        else -> {
                            if (bit < s) riempi()
                            val v = ((acc ushr (bit - s)) and ((1L shl s) - 1)).toInt()
                            bit -= s
                            if (v < (1 shl (s - 1))) v - (1 shl s) + 1 else v
                        }
                    }
                    val i = r * riga + c * nf + k
                    val pred = when {
                        r == primaRiga && c == 0 -> iniziale
                        r == primaRiga -> out[i - nf]
                        c == 0 -> out[i - riga]
                        else -> {
                            val ra = out[i - nf]
                            val rb = out[i - riga]
                            val rc = out[i - riga - nf]
                            when (predittore) {
                                1 -> ra
                                2 -> rb
                                3 -> rc
                                4 -> ra + rb - rc
                                5 -> ra + ((rb - rc) shr 1)
                                6 -> rb + ((ra - rc) shr 1)
                                7 -> (ra + rb) ushr 1
                                else -> ra
                            }
                        }
                    }
                    out[i] = (pred + diff) and 0xFFFF
                }
                mcu++
            }
        }
        if (pt > 0) for (i in out.indices) out[i] = out[i] shl pt
        return Fotogramma(x, y, nf, out)
    }
}
