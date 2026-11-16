package it.vincenzo.fototool.motore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lettore TIFF minimo: quanto basta per i DNG (IFD, SubIFD, tag numerici e dati grezzi). */
class Tiff(buf: ByteBuffer) {
    val b: ByteBuffer = buf.duplicate()

    class Voce(val tag: Int, val tipo: Int, val n: Int, val pos: Int)

    class Ifd(val voci: Map<Int, Voce>, val tiff: Tiff) {
        operator fun contains(tag: Int) = tag in voci
        fun interi(tag: Int): LongArray? = voci[tag]?.let { tiff.interi(it) }
        fun intero(tag: Int, predefinito: Long = 0): Long = interi(tag)?.firstOrNull() ?: predefinito
        fun reali(tag: Int): DoubleArray? = voci[tag]?.let { tiff.reali(it) }
        fun byte(tag: Int): ByteArray? = voci[tag]?.let { tiff.byte(it) }
        fun testo(tag: Int): String? = byte(tag)?.let { String(it, Charsets.ISO_8859_1).trimEnd('\u0000').trim() }
    }

    init {
        b.order(
            when (val o = b.getShort(0).toInt() and 0xFFFF) {
                0x4949 -> ByteOrder.LITTLE_ENDIAN
                0x4D4D -> ByteOrder.BIG_ENDIAN
                else -> error("non è un file TIFF/DNG (intestazione ${o.toString(16)})")
            }
        )
        require((b.getShort(2).toInt() and 0xFFFF) == 42) { "TIFF non valido" }
    }

    val ifd0: Ifd by lazy { leggiIfd(b.getInt(4).toLong() and 0xFFFFFFFFL) }

    fun leggiIfd(offset: Long): Ifd {
        val o = offset.toInt()
        val n = b.getShort(o).toInt() and 0xFFFF
        val voci = HashMap<Int, Voce>(n * 2)
        for (i in 0 until n) {
            val e = o + 2 + i * 12
            val tag = b.getShort(e).toInt() and 0xFFFF
            val tipo = b.getShort(e + 2).toInt() and 0xFFFF
            val cnt = b.getInt(e + 4)
            val dim = dimTipo(tipo).toLong() * (cnt.toLong() and 0xFFFFFFFFL)
            val pos = if (dim <= 4) e + 8 else b.getInt(e + 8)
            voci[tag] = Voce(tag, tipo, cnt, pos)
        }
        return Ifd(voci, this)
    }

    /** Tutte le IFD del file: IFD0, le successive, le SubIFD (anche annidate). */
    fun tutte(): List<Ifd> {
        val out = mutableListOf<Ifd>()
        val visti = HashSet<Long>()
        fun visita(off: Long) {
            if (off <= 0 || off >= b.limit() || !visti.add(off)) return
            val ifd = leggiIfd(off)
            out += ifd
            ifd.interi(330)?.forEach { visita(it) } // SubIFDs
            val n = b.getShort(off.toInt()).toInt() and 0xFFFF
            val prossima = b.getInt(off.toInt() + 2 + n * 12).toLong() and 0xFFFFFFFFL
            visita(prossima)
        }
        visita(b.getInt(4).toLong() and 0xFFFFFFFFL)
        return out
    }

    private fun dimTipo(t: Int) = when (t) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> 1
    }

    fun interi(v: Voce): LongArray = LongArray(v.n) { i ->
        when (v.tipo) {
            1, 7 -> (b.get(v.pos + i).toLong() and 0xFF)
            6 -> b.get(v.pos + i).toLong()
            3 -> (b.getShort(v.pos + 2 * i).toLong() and 0xFFFF)
            8 -> b.getShort(v.pos + 2 * i).toLong()
            4, 13 -> (b.getInt(v.pos + 4 * i).toLong() and 0xFFFFFFFFL)
            9 -> b.getInt(v.pos + 4 * i).toLong()
            5, 10, 11, 12 -> reali(v)[i].toLong()
            else -> 0
        }
    }

    fun reali(v: Voce): DoubleArray = DoubleArray(v.n) { i ->
        when (v.tipo) {
            5 -> {
                val num = b.getInt(v.pos + 8 * i).toLong() and 0xFFFFFFFFL
                val den = b.getInt(v.pos + 8 * i + 4).toLong() and 0xFFFFFFFFL
                if (den == 0L) 0.0 else num.toDouble() / den
            }
            10 -> {
                val num = b.getInt(v.pos + 8 * i)
                val den = b.getInt(v.pos + 8 * i + 4)
                if (den == 0) 0.0 else num.toDouble() / den
            }
            11 -> b.getFloat(v.pos + 4 * i).toDouble()
            12 -> b.getDouble(v.pos + 8 * i)
            else -> interi(v)[i].toDouble()
        }
    }

    fun byte(v: Voce): ByteArray = ByteArray(v.n) { b.get(v.pos + it) }

    /** Una fetta del file (dati di una striscia o di una tessera). */
    fun fetta(offset: Long, lunghezza: Long): ByteBuffer {
        val d = b.duplicate()
        d.position(offset.toInt())
        d.limit((offset + lunghezza).toInt())
        return d.slice().order(b.order())
    }
}
