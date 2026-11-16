package it.vincenzo.fototool.motore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class MotoreTest {
    private fun risorsa(nome: String): ByteArray =
        javaClass.getResourceAsStream("/$nome")?.use { it.readBytes() } ?: error("manca $nome")

    private fun attesi(nome: String) = ByteBuffer.wrap(risorsa(nome)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

    /** Casi generati da strumenti/ljpeg_codifica.py e imagecodecs: 1-3 componenti, predittori 1-7, riavvii. */
    @Test
    fun jpegSenzaPerdita() {
        for (n in 0..9) {
            val fr = Ljpeg.decodifica(risorsa("caso$n.ljpg"))
            val a = attesi("caso$n.raw")
            assertEquals("caso $n: numero di campioni", a.limit(), fr.campioni.size)
            for (i in fr.campioni.indices) assertEquals("caso $n campione $i", a.get(i).toInt() and 0xFFFF, fr.campioni[i])
        }
    }

    @Test
    fun dngSintetico() {
        val dng = Dng.leggi(ByteBuffer.wrap(risorsa("sintetico.dng")))
        assertEquals(320, dng.larghezza)
        assertEquals(256, dng.altezza)
        assertEquals(listOf(0, 1, 1, 2), dng.cfa.toList())
        assertEquals(4, dng.gainMaps.size)
        assertEquals(6, dng.orientamento)
        val a = attesi("sintetico.raw")
        for (i in 0 until 320 * 256) assertEquals("RAW $i", a.get(i), dng.raw[i])

        val (w, h) = Sviluppo.dimensioni(dng)
        assertEquals(256 to 320, w to h)
        val img = IntArray(w * h)
        val esito = Sviluppo.sviluppa(dng, Opzioni()) { x, y, bw, bh, px ->
            for (r in 0 until bh) System.arraycopy(px, r * bw, img, (y + r) * w + x, bw)
        }
        fun rgb(x: Int, y: Int) = img[y * w + x].let { Triple((it shr 16) and 255, (it shr 8) and 255, it and 255) }
        // dopo la rotazione oraria: toppa rossa in alto a destra, verde in alto a sinistra, blu in basso
        val (r1, g1, b1) = rgb(200, 70); assertTrue("rosso $r1 $g1 $b1", r1 > g1 + 40 && r1 > b1 + 40)
        val (r2, g2, b2) = rgb(95, 75); assertTrue("verde $r2 $g2 $b2", g2 > r2 + 30 && g2 > b2 + 20)
        val (r3, g3, b3) = rgb(135, 240); assertTrue("blu $r3 $g3 $b3", b3 > r3 + 30 && b3 > g3 + 10)
        // luce bruciata (riga 0..20 dell'originale, ora a destra): neutra, niente dominante magenta
        val (r4, g4, b4) = rgb(250, 160); assertTrue("bianco neutro $r4 $g4 $b4", abs(r4 - g4) < 12 && abs(b4 - g4) < 12 && g4 > 190)
        // la gain map HDR alza solo le luci
        assertTrue("gain map sulle luci", (esito.gainMap[160 / 4 * esito.gainLarghezza + 63].toInt() and 255) > 100)
        assertTrue("gain map sulle ombre", (esito.gainMap[160 / 4 * esito.gainLarghezza + 10].toInt() and 255) < 5)
    }

    /** I DNG HDR+ del Pixel 8 Pro non hanno AsShotNeutral: il bianco si ricava dal JPEG di Google. */
    @Test
    fun biancoDalJpegDiGoogle() {
        val conBianco = Dng.leggi(ByteBuffer.wrap(risorsa("sintetico.dng")))
        val (w, h) = Sviluppo.dimensioni(conBianco)
        val img = IntArray(w * h)
        Sviluppo.sviluppa(conBianco, Opzioni()) { x, y, bw, bh, px ->
            for (r in 0 until bh) System.arraycopy(px, r * bw, img, (y + r) * w + x, bw)
        }
        val senza = Dng.leggi(ByteBuffer.wrap(risorsa("sintetico_senza_neutro.dng")))
        assertTrue("senza bianco nel DNG", !senza.neutroDaScatto)
        val n = Sviluppo.stimaNeutro(senza, Riferimento(w, h, img))
        val vero = doubleArrayOf(0.5, 1.0, 0.62)
        for (i in 0..2) assertTrue("neutro ${n.toList()} invece di ${vero.toList()}", abs(n[i] - vero[i]) / vero[i] < 0.08)
    }

    @Test
    fun coloreBianco() {
        val bianco = Colore.per(Colore.XYZ50_A_P3, doubleArrayOf(0.96422, 1.0, 0.82521))
        bianco.forEach { assertEquals(1.0, it, 0.002) }
        assertEquals(1.0, Colore.LUMA.sum(), 1e-6)
    }

    @Test
    fun curvaMonotona() {
        var prima = -1f
        var y = 0f
        while (y < 64f) {
            val v = Sviluppo.curva(y)
            assertTrue(v >= prima && v <= 1f)
            prima = v; y += 0.01f
        }
        assertEquals(0.18f, Sviluppo.curva(0.18f), 0.002f)
    }
}
