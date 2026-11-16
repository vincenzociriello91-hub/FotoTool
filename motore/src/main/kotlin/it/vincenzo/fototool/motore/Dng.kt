package it.vincenzo.fototool.motore

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.stream.IntStream

/**
 * Un DNG letto: dati RAW dell'area attiva (16 bit senza segno in uno ShortArray) e tutti i metadati
 * che servono a svilupparlo. Tutto ciò che non sa trattare finisce in [note] e quindi nel rapporto.
 */
class Dng private constructor(
    val larghezza: Int,
    val altezza: Int,
    val raw: ShortArray,
    /** Colore di ciascuna posizione del quadrato 2x2 del filtro: 0 = rosso, 1 = verde, 2 = blu. */
    val cfa: IntArray,
    val nero: FloatArray,
    val bianco: Float,
    val neutro: DoubleArray,
    /** Falso se il DNG non dice il bianco dello scatto: allora lo stima lo sviluppo (dal JPEG di Google). */
    val neutroDaScatto: Boolean,
    val colore: Colore.Profilo,
    val esposizioneBase: Double,
    val profiloRumore: DoubleArray?,
    val gainMaps: List<GainMap>,
    val orientamento: Int,
    /** Ritaglio predefinito dentro l'area attiva: x, y, larghezza, altezza. */
    val ritaglio: IntArray,
    val exif: Exif,
    val note: Map<String, String>,
) {
    class Exif(
        val marca: String?, val modello: String?, val software: String?, val data: String?,
        val tempo: Double?, val diaframma: Double?, val iso: Double?, val focale: Double?,
    )

    /** Mappa di guadagno (OpcodeList2, id 9): correzione dell'ombreggiatura dell'obiettivo per un piano del filtro. */
    class GainMap(
        val alto: Int, val sinistra: Int, val basso: Int, val destra: Int,
        val passoRighe: Int, val passoColonne: Int,
        val puntiV: Int, val puntiH: Int,
        val spaziaturaV: Double, val spaziaturaH: Double, val origineV: Double, val origineH: Double,
        val valori: FloatArray,
    ) {
        /** Guadagno nel punto relativo (0..1) dell'immagine, interpolato tra i nodi. */
        fun guadagno(vRel: Double, hRel: Double): Float {
            val fy = ((vRel - origineV) / spaziaturaV).coerceIn(0.0, (puntiV - 1).toDouble())
            val fx = ((hRel - origineH) / spaziaturaH).coerceIn(0.0, (puntiH - 1).toDouble())
            val y0 = fy.toInt().coerceAtMost(puntiV - 2).coerceAtLeast(0)
            val x0 = fx.toInt().coerceAtMost(puntiH - 2).coerceAtLeast(0)
            val y1 = (y0 + 1).coerceAtMost(puntiV - 1)
            val x1 = (x0 + 1).coerceAtMost(puntiH - 1)
            val ty = (fy - y0).toFloat()
            val tx = (fx - x0).toFloat()
            val a = valori[y0 * puntiH + x0]
            val b = valori[y0 * puntiH + x1]
            val c = valori[y1 * puntiH + x0]
            val d = valori[y1 * puntiH + x1]
            return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
        }
    }

    /** Valore RAW a (riga, colonna) dell'area attiva come intero senza segno. */
    fun campione(r: Int, c: Int): Int = raw[r * larghezza + c].toInt() and 0xFFFF

    /** Colore del filtro in (riga, colonna). */
    fun coloreIn(r: Int, c: Int): Int = cfa[((r and 1) shl 1) or (c and 1)]

    companion object {
        private const val CFA = 32803
        private const val LINEARE = 34892

        fun leggi(buf: ByteBuffer): Dng {
            val t = Tiff(buf)
            val tutte = t.tutte()
            val ifd0 = t.ifd0
            val note = linkedMapOf<String, String>()
            val rawIfd = tutte.firstOrNull { it.intero(254, 0) == 0L && it.intero(262) == CFA.toLong() }
                ?: tutte.firstOrNull { it.intero(262) == LINEARE.toLong() }?.let { error("DNG già demosaicizzato (LinearRaw): non ancora supportato") }
                ?: error("nessuna immagine RAW nel DNG")

            // Cerca un tag prima nell'IFD del RAW, poi nell'IFD0 (il DNG permette entrambe).
            fun reali(tag: Int) = rawIfd.reali(tag) ?: ifd0.reali(tag)
            fun interi(tag: Int) = rawIfd.interi(tag) ?: ifd0.interi(tag)
            fun byte(tag: Int) = rawIfd.byte(tag) ?: ifd0.byte(tag)

            val w = rawIfd.intero(256).toInt()
            val h = rawIfd.intero(257).toInt()
            val bps = rawIfd.intero(258, 16).toInt()
            val comp = rawIfd.intero(259, 1).toInt()
            val spp = rawIfd.intero(277, 1).toInt()
            require(spp == 1) { "RAW con $spp campioni per pixel non supportato" }
            note["formato"] = "${w}x$h, $bps bit, compressione $comp, ordine ${t.b.order()}"

            val t0 = System.nanoTime()
            val pieno = ShortArray(w * h)
            val tessere = rawIfd.interi(322) != null
            val tw = if (tessere) rawIfd.intero(322).toInt() else w
            val th = if (tessere) rawIfd.intero(323).toInt() else rawIfd.intero(278, h.toLong()).toInt().coerceAtMost(h)
            val offs = (if (tessere) rawIfd.interi(324) else rawIfd.interi(273)) ?: error("dati RAW mancanti")
            val lens = (if (tessere) rawIfd.interi(325) else rawIfd.interi(279)) ?: error("lunghezze RAW mancanti")
            val perRiga = (w + tw - 1) / tw
            note["blocchi"] = "${offs.size} ${if (tessere) "tessere ${tw}x$th" else "strisce da $th righe"}"
            IntStream.range(0, offs.size).parallel().forEach { i ->
                val x0 = (i % perRiga) * tw
                val y0 = (i / perRiga) * th
                val fetta = t.fetta(offs[i], lens[i])
                when (comp) {
                    7 -> {
                        val dati = ByteArray(fetta.remaining()).also { fetta.get(it) }
                        val fr = Ljpeg.decodifica(dati)
                        val s = fr.campioni
                        // I campioni riempiono la tessera riga per riga, qualunque sia la forma del fotogramma.
                        var k = 0
                        var yy = 0
                        while (yy < th && k < s.size) {
                            val y = y0 + yy
                            var xx = 0
                            while (xx < tw && k < s.size) {
                                val x = x0 + xx
                                if (x < w && y < h) pieno[y * w + x] = s[k].toShort()
                                k++; xx++
                            }
                            yy++
                        }
                    }
                    1 -> {
                        require(bps == 16 || bps == 8) { "RAW non compresso a $bps bit non supportato" }
                        val righe = minOf(th, h - y0)
                        for (yy in 0 until righe) for (xx in 0 until minOf(tw, w - x0)) {
                            val v = if (bps == 16) fetta.getShort((yy * tw + xx) * 2).toInt() and 0xFFFF
                            else fetta.get(yy * tw + xx).toInt() and 0xFF
                            pieno[(y0 + yy) * w + x0 + xx] = v.toShort()
                        }
                    }
                    else -> error("compressione RAW $comp non supportata")
                }
            }
            note["decodifica"] = "${(System.nanoTime() - t0) / 1_000_000} ms"

            // Area attiva (in alto, a sinistra, in basso, a destra)
            val aa = interi(50829)?.map { it.toInt() } ?: listOf(0, 0, h, w)
            val aw = aa[3] - aa[1]
            val ah = aa[2] - aa[0]
            val raw = if (aw == w && ah == h) pieno else ShortArray(aw * ah).also { dst ->
                for (y in 0 until ah) System.arraycopy(pieno, (y + aa[0]) * w + aa[1], dst, y * aw, aw)
            }
            if (aw != w || ah != h) note["area attiva"] = aa.joinToString()

            // Filtro colore
            val dim = interi(33421)?.map { it.toInt() } ?: listOf(2, 2)
            require(dim == listOf(2, 2)) { "filtro colore ${dim.joinToString("x")} non supportato (solo Bayer 2x2)" }
            val pattern = byte(33422)?.map { it.toInt() } ?: error("CFAPattern mancante")
            val piano = byte(50710)?.map { it.toInt() } ?: listOf(0, 1, 2)
            val cfa = IntArray(4) { piano.indexOf(pattern[it]).also { c -> require(c in 0..2) { "colore del filtro sconosciuto" } } }
            // Con un'area attiva che parte da una riga/colonna dispari il disegno resta riferito all'area attiva (regola DNG).
            note["filtro"] = cfa.joinToString("") { "RGB"[it].toString() }

            // Nero e bianco
            val rip = interi(50713)?.map { it.toInt() } ?: listOf(1, 1)
            val neri = reali(50714) ?: doubleArrayOf(0.0)
            val nero = FloatArray(4) { pos ->
                val r = pos shr 1
                val c = pos and 1
                neri[((r % rip[0]) * rip[1] + (c % rip[1])).coerceAtMost(neri.size - 1)].toFloat()
            }
            if (reali(50715) != null || reali(50716) != null) note["nero per riga/colonna"] = "presente, non applicato"
            if (interi(50712) != null) note["tabella di linearizzazione"] = "presente, non applicata"
            val bianco = (reali(50717)?.firstOrNull() ?: ((1 shl bps) - 1).toDouble()).toFloat()

            // Colore
            val profiloTmp = Colore.Profilo(
                matrice1 = reali(50721), matrice2 = reali(50722),
                calibrazione1 = reali(50723), calibrazione2 = reali(50724),
                avanti1 = reali(50964), avanti2 = reali(50965),
                bilanciamentoAnalogico = reali(50727),
                illuminante1 = interi(50778)?.firstOrNull()?.toInt() ?: 0,
                illuminante2 = interi(50779)?.firstOrNull()?.toInt() ?: 0,
            )
            val xy = reali(50729)
            val neutro = reali(50728)?.also { note["bilanciamento"] = "AsShotNeutral" }
                ?: xy?.takeIf { it.size >= 2 }?.let { Colore.neutroDaXY(profiloTmp, it[0], it[1]) }?.also { note["bilanciamento"] = "AsShotWhiteXY ${xy.joinToString()}" }
                ?: doubleArrayOf(1.0, 1.0, 1.0).also { note["bilanciamento"] = "assente nel DNG: stimato in sviluppo" }
            val daScatto = reali(50728) != null || (xy?.size ?: 0) >= 2
            val profilo = profiloTmp

            // Opcode: la lista 2 (sul RAW) contiene la correzione dell'ombreggiatura dell'obiettivo
            val gainMaps = mutableListOf<GainMap>()
            for ((tag, nome) in listOf(51008 to "OpcodeList1", 51009 to "OpcodeList2", 51022 to "OpcodeList3")) {
                val dati = byte(tag) ?: continue
                val elenco = leggiOpcode(dati, if (tag == 51009) gainMaps else null)
                note[nome] = elenco.joinToString()
            }

            val crOrig = reali(50719)?.map { it.toInt() } ?: listOf(0, 0)
            val crDim = reali(50720)?.map { it.toInt() } ?: listOf(aw, ah)
            val ritaglio = intArrayOf(crOrig[0], crOrig[1], crDim[0].coerceAtMost(aw - crOrig[0]), crDim[1].coerceAtMost(ah - crOrig[1]))

            val exifIfd = ifd0.interi(34665)?.firstOrNull()?.let { runCatching { t.leggiIfd(it) }.getOrNull() }
            fun exif(tag: Int) = exifIfd?.reali(tag)?.firstOrNull() ?: reali(tag)?.firstOrNull()
            val ex = Exif(
                marca = ifd0.testo(271), modello = ifd0.testo(272) ?: ifd0.testo(50708), software = ifd0.testo(305),
                data = exifIfd?.testo(36867) ?: ifd0.testo(36867) ?: ifd0.testo(306),
                tempo = exif(33434), diaframma = exif(33437), iso = exif(34855), focale = exif(37386),
            )

            return Dng(
                aw, ah, raw, cfa, nero, bianco, neutro, daScatto, profilo,
                esposizioneBase = reali(50730)?.firstOrNull() ?: 0.0,
                profiloRumore = reali(51041),
                gainMaps = gainMaps,
                orientamento = ifd0.intero(274, 1).toInt(),
                ritaglio = ritaglio,
                exif = ex,
                note = note,
            )
        }

        /** Legge una lista di opcode (sempre big-endian); le GainMap vengono messe in [gm]. */
        private fun leggiOpcode(dati: ByteArray, gm: MutableList<GainMap>?): List<String> {
            val b = ByteBuffer.wrap(dati).order(ByteOrder.BIG_ENDIAN)
            val n = b.int
            val nomi = mutableListOf<String>()
            repeat(n) {
                val id = b.int
                b.int // versione
                val flag = b.int
                val lun = b.int
                val inizio = b.position()
                nomi += (NOMI_OPCODE[id] ?: "Opcode$id") + if (flag and 1 != 0) "(facoltativo)" else ""
                if (id == 9 && gm != null) {
                    val alto = b.int; val sx = b.int; val basso = b.int; val dx = b.int
                    b.int; b.int // piano, piani
                    val pr = b.int; val pc = b.int
                    val pv = b.int; val ph = b.int
                    val sv = b.double; val sh = b.double; val ov = b.double; val oh = b.double
                    val mp = b.int
                    val valori = FloatArray(pv * ph) { b.float.also { _ -> repeat(mp - 1) { b.float } } }
                    gm += GainMap(alto, sx, basso, dx, pr, pc, pv, ph, sv, sh, ov, oh, valori)
                }
                b.position(inizio + lun)
            }
            return nomi
        }

        private val NOMI_OPCODE = mapOf(
            1 to "WarpRectilinear", 2 to "WarpFisheye", 3 to "FixVignetteRadial", 4 to "FixBadPixelsConstant",
            5 to "FixBadPixelsList", 6 to "TrimBounds", 7 to "MapTable", 8 to "MapPolynomial", 9 to "GainMap",
            10 to "DeltaPerRow", 11 to "DeltaPerColumn", 12 to "ScalePerRow", 13 to "ScalePerColumn",
            14 to "WarpRectilinear2",
        )
    }
}
