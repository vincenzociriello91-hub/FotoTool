package it.vincenzo.fototool.motore

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Uno stile di sviluppo: pochi numeri che cambiano il carattere della foto. */
data class Stile(
    val nome: String,
    /** Esposizione in più o in meno rispetto al riferimento, in stop. */
    val esposizione: Float = 0f,
    /** Quanto si alzano le ombre (1 = niente, più basso = più compressione). */
    val compressioneOmbre: Float = 0.78f,
    /** Quanto si abbassano le luci (1 = niente). */
    val compressioneLuci: Float = 0.62f,
    /** Contrasto locale (chiarezza): 1 = come la scena. */
    val dettaglio: Float = 1.08f,
    /** Contrasto globale a S: 0 = nessuno. */
    val contrasto: Float = 0.12f,
    val saturazione: Float = 1.0f,
    val vivacita: Float = 0.08f,
    val nitidezza: Float = 0.85f,
    val rumoreLuma: Float = 1.0f,
    val rumoreCroma: Float = 1.0f,
    val biancoNero: Boolean = false,
    /** Quanto seguire tono e saturazione del JPEG di Google (0 = per niente, 1 = uguale). */
    val tonoGoogle: Float = 0.95f,
)

object Stili {
    val NATURALE = Stile("Naturale")
    val VIVIDO = Stile("Vivido", compressioneOmbre = 0.74f, compressioneLuci = 0.58f, dettaglio = 1.18f, contrasto = 0.22f,
        saturazione = 1.10f, vivacita = 0.25f, nitidezza = 1.0f)
    val MORBIDO = Stile("Morbido", compressioneOmbre = 0.72f, compressioneLuci = 0.6f, dettaglio = 1.0f, contrasto = 0.0f,
        saturazione = 0.96f, vivacita = 0.05f, nitidezza = 0.45f, rumoreLuma = 1.3f, tonoGoogle = 0.5f)
    val BIANCO_NERO = Stile("Bianco e nero", dettaglio = 1.15f, contrasto = 0.25f, nitidezza = 0.9f, biancoNero = true)
    val TUTTI = listOf(NATURALE, VIVIDO, MORBIDO, BIANCO_NERO)
    fun perNome(n: String?) = TUTTI.firstOrNull { it.nome == n } ?: NATURALE
}

/** Il JPEG di Google rimpicciolito: pixel ARGB con curva sRGB (Display P3 o sRGB). */
class Riferimento(val larghezza: Int, val altezza: Int, val pixel: IntArray)

class Opzioni(
    val stile: Stile = Stili.NATURALE,
    val riferimento: Riferimento? = null,
    /** Se presente, sceglie lo stile in base alla scena riconosciuta (i gusti imparati). */
    val stilePerScena: ((String) -> Stile)? = null,
    /** Luminosità massima della versione HDR rispetto al bianco SDR (Ultra HDR). */
    val margineHdr: Float = 4f,
)

class Esito(
    val larghezza: Int,
    val altezza: Int,
    /** Gain map Ultra HDR: un byte per blocco 4x4, già ruotata come la foto. */
    val gainMap: ByteArray,
    val gainLarghezza: Int,
    val gainAltezza: Int,
    val rapportoMax: Float,
    val scena: String,
    val stile: String,
    val parametri: LinkedHashMap<String, Any?>,
    val tempi: LinkedHashMap<String, Long>,
)

/**
 * Sviluppo di un DNG di Pixel Camera:
 * analisi a bassa risoluzione (esposizione, luci e ombre per zone) e poi resa a piena risoluzione a fasce,
 * per stare nella memoria del telefono anche a 50 MP.
 */
object Sviluppo {
    private const val GRIGIO = 0.18f
    private const val B_CURVA = 0.3f
    /** Sensibilità ai bordi delle zone (in stop²): più basso = bordi più netti, meno aloni. */
    private const val EPS_ZONE = 0.05f

    /** Curva di resa SDR: da luminanza di scena a luminanza dello schermo, con spalla morbida verso 1. */
    private val D_CURVA = run { val u = GRIGIO * (GRIGIO + B_CURVA); u * (1 - GRIGIO) / GRIGIO }
    fun curva(y: Float): Float {
        if (y <= 0f) return 0f
        val u = y * (y + B_CURVA)
        return u / (u + D_CURVA)
    }

    /** Guadagno della versione HDR, sulla luminanza di scena: solo le luci, da un stop sopra il grigio fino al margine. */
    fun rapportoHdr(y: Float, margine: Float): Float {
        if (y <= 0.36f) return 1f
        val t = (log2(y / 0.36f) / 2.5f).coerceIn(0f, 1f)
        val s = t * t * (3 - 2 * t)
        return 1f + (margine - 1f) * s
    }

    /** Dimensioni della foto finale (dopo ritaglio e rotazione): servono per preparare la tela prima dello sviluppo. */
    fun dimensioni(dng: Dng): Pair<Int, Int> {
        val (cw, ch) = dng.ritaglio[2] to dng.ritaglio[3]
        return if (dng.orientamento in setOf(5, 6, 7, 8)) ch to cw else cw to ch
    }

    fun sviluppa(dng: Dng, opz: Opzioni, scrivi: (x: Int, y: Int, w: Int, h: Int, px: IntArray) -> Unit): Esito {
        val tempi = linkedMapOf<String, Long>()
        val par = linkedMapOf<String, Any?>()
        var t = System.nanoTime()
        fun tappa(nome: String) { val n = System.nanoTime(); tempi[nome] = (n - t) / 1_000_000; t = n }
        val W = dng.larghezza
        val H = dng.altezza

        // ---------- colore ----------
        val neutro = if (dng.neutroDaScatto) dng.neutro else stimaNeutro(dng, opz.riferimento)
        if (!dng.neutroDaScatto) par["bianco stimato da"] = if (opz.riferimento != null) "JPEG di Google" else "media della scena"
        val col = Colore.calcola(dng.colore, neutro)
        val M = Array(3) { r -> FloatArray(3) { c -> col.matrice[r][c].toFloat() } }
        val wb = FloatArray(3) { (1.0 / neutro[it]).toFloat() }
        val L = Colore.LUMA.map { it.toFloat() }
        val (lr, lg, lb) = L
        par["temperatura colore (K)"] = col.temperatura.roundToInt()
        par["metodo colore"] = col.metodo
        par["neutro"] = neutro.joinToString { "%.4f".format(it) }
        par["matrice verso P3"] = col.matrice.joinToString(" | ") { r -> r.joinToString { "%.3f".format(it) } }

        // ---------- normalizzazione + correzione obiettivo ----------
        val scala = FloatArray(4) { 1f / (dng.bianco - dng.nero[it]) }
        val mappe = dng.gainMaps
        par["correzione obiettivo"] = if (mappe.isEmpty()) "nessuna mappa nel DNG" else "${mappe.size} mappe ${mappe[0].puntiH}x${mappe[0].puntiV}"
        /** Valore lineare bilanciato (bianco del sensore = 1/neutro) del pixel RAW (r, c). */
        fun lineare(r: Int, c: Int, guad: Float): Float {
            val pos = ((r and 1) shl 1) or (c and 1)
            val v = ((dng.campione(r, c) - dng.nero[pos]) * scala[pos]).coerceAtLeast(0f)
            return v * guad * wb[dng.cfa[pos]]
        }
        /** Guadagno delle mappe dell'obiettivo nel pixel (r, c). */
        fun ombreggiatura(r: Int, c: Int): Float {
            if (mappe.isEmpty()) return 1f
            for (g in mappe) {
                if (r < g.alto || r >= g.basso || c < g.sinistra || c >= g.destra) continue
                if ((r - g.alto) % g.passoRighe != 0 || (c - g.sinistra) % g.passoColonne != 0) continue
                return g.guadagno((r + 0.5) / H, (c + 0.5) / W)
            }
            return 1f
        }

        // ---------- analisi a bassa risoluzione ----------
        val k = max(1, ((W / 2.0) / 1024).roundToInt())
        val blocco = 2 * k
        val lw = W / blocco
        val lh = H / blocco
        val lowR = FloatArray(lw * lh)
        val lowG = FloatArray(lw * lh)
        val lowB = FloatArray(lw * lh)
        var saturi = 0L
        val satPerRiga = LongArray(lh)
        Filtri.perRighe(lh) { ly ->
            val acc = FloatArray(3)
            val cnt = IntArray(3)
            var sat = 0L
            for (lx in 0 until lw) {
                acc.fill(0f); cnt.fill(0)
                val r0 = ly * blocco
                val c0 = lx * blocco
                // Ombreggiatura costante nel blocco: una valutazione per colore al centro basta.
                val gc = FloatArray(4) { pos -> ombreggiatura(r0 + k - (k and 1) + (pos shr 1), c0 + k - (k and 1) + (pos and 1)) }
                for (r in r0 until r0 + blocco) for (c in c0 until c0 + blocco) {
                    val pos = ((r and 1) shl 1) or (c and 1)
                    val grezzo = dng.campione(r, c)
                    if (grezzo >= dng.bianco * 0.98f) sat++
                    val ch = dng.cfa[pos]
                    acc[ch] += lineare(r, c, gc[pos]); cnt[ch]++
                }
                val cr = min(acc[0] / max(1, cnt[0]), 1f)
                val cg = min(acc[1] / max(1, cnt[1]), 1f)
                val cb = min(acc[2] / max(1, cnt[2]), 1f)
                val i = ly * lw + lx
                lowR[i] = M[0][0] * cr + M[0][1] * cg + M[0][2] * cb
                lowG[i] = M[1][0] * cr + M[1][1] * cg + M[1][2] * cb
                lowB[i] = M[2][0] * cr + M[2][1] * cg + M[2][2] * cb
            }
            satPerRiga[ly] = sat
        }
        saturi = satPerRiga.sum()
        par["pixel saturati (%)"] = "%.3f".format(100.0 * saturi / (lw.toLong() * lh * blocco * blocco))
        val n = lw * lh
        val logY = FloatArray(n) { i -> log2(max(lr * lowR[i] + lg * lowG[i] + lb * lowB[i], 1e-6f)) }
        tappa("analisi")

        // percentili della luminanza di scena
        val ordinati = logY.copyOf().also { it.sort() }
        fun perc(p: Double) = ordinati[((n - 1) * p).toInt()]
        val p1 = perc(0.01); val p50 = perc(0.5); val p99 = perc(0.99)
        val gamma = p99 - p1
        par["gamma dinamica scena (stop)"] = "%.1f".format(gamma)

        // scena: notte / controluce / giorno
        val e = dng.exif
        val ev100 = if (e.tempo != null && e.diaframma != null && e.iso != null && e.tempo > 0 && e.iso > 0)
            log2(e.diaframma * e.diaframma / e.tempo) - log2(e.iso / 100.0) else null
        val scena = when {
            ev100 != null && ev100 < 4.5 -> "notte"
            gamma > 9.5f -> "controluce"
            else -> "giorno"
        }
        par["EV100"] = ev100?.let { "%.1f".format(it) }
        par["scena"] = scena
        val stile = opz.stilePerScena?.invoke(scena) ?: opz.stile
        par["stile"] = stile.nome

        // base per luci e ombre per zone: filtro guidato sul logaritmo della luminanza
        val raggio = max(4, min(lw, lh) / 48)
        val (coA, coB) = Filtri.coefficienti(logY, logY, lw, lh, raggio, EPS_ZONE)
        val base = FloatArray(n) { coA[it] * logY[it] + coB[it] }
        tappa("zone")

        // Luci e ombre per zone solo quanto serve: nulle sotto 5 stop di gamma dinamica, piene da 11 in su.
        // (Con compressione fissa le scene normali venivano piatte e slavate: rapporti del 16/11/2026.)
        val forzaZone = ((gamma - 5f) / 6f).coerceIn(0f, 1f)
        val cOmbre = 1f - (1f - stile.compressioneOmbre) * forzaZone
        val cLuci = 1f - (1f - stile.compressioneLuci) * forzaZone
        par["forza zone"] = "%.2f".format(forzaZone)
        val mid = log2(GRIGIO)
        /** Guadagno locale (stop) per una luminanza di base b dopo l'esposizione. */
        fun guadagnoZona(b: Float, dettaglio: Float): Float {
            val c = if (b > mid) cLuci else cOmbre
            val g = (mid + (b - mid) * c) - b
            return (g + (stile.dettaglio - 1f) * dettaglio.coerceIn(-1f, 1f)).coerceIn(-3f, 2.5f)
        }

        // esposizione: la mediana dello schermo segue quella del JPEG di Google (o un grigio medio)
        val obiettivo = opz.riferimento?.let { medianaRiferimento(it, lr, lg, lb) }
        val bersaglio = (obiettivo ?: if (scena == "notte") 0.11f else GRIGIO) * 2f.pow(stile.esposizione)
        par["mediana JPEG di Google"] = obiettivo?.let { "%.3f".format(it) } ?: "nessun riferimento"
        fun medianaResa(ev: Float): Float {
            val isto = IntArray(1024)
            for (i in 0 until n) {
                val b = base[i] + ev
                val y = 2f.pow(logY[i] + ev + guadagnoZona(b, logY[i] - base[i]))
                isto[(curva(y) * 1023).toInt().coerceIn(0, 1023)]++
            }
            var s = 0
            for (j in 0 until 1024) { s += isto[j]; if (s >= n / 2) return j / 1023f }
            return 1f
        }
        var basso = -6f
        var alto = 8f
        repeat(14) {
            val m = (basso + alto) / 2
            if (medianaResa(m) < bersaglio) basso = m else alto = m
        }
        val ev = ((basso + alto) / 2).coerceIn(-4f, 6f)
        par["esposizione applicata (stop)"] = "%.2f".format(ev)
        par["esposizione base DNG (stop)"] = "%.2f".format(dng.esposizioneBase)
        par["mediana finale"] = "%.3f".format(medianaResa(ev))
        val guad = FloatArray(n) { i -> guadagnoZona(base[i] + ev, logY[i] - base[i]) }
        par["luci e ombre (stop) min/max"] = "%.2f / %.2f".format(guad.min(), guad.max())
        par["zone"] = "raggio $raggio su ${lw}x$lh, eps $EPS_ZONE"
        tappa("esposizione")

        // ---------- tono e saturazione come il JPEG di Google ----------
        val tono = opz.riferimento?.let { rf ->
            val lut = curvaDaRiferimento(rf, n, lr, lg, lb, stile) { i ->
                val y = 2f.pow(logY[i] + ev + guad[i])
                val s = if (y > 1e-6f) curva(y) / y else 0f
                floatArrayOf(lowR[i] * 2f.pow(ev + guad[i]) * s, lowG[i] * 2f.pow(ev + guad[i]) * s, lowB[i] * 2f.pow(ev + guad[i]) * s)
            }
            par["tono da Google"] = "forza ${stile.tonoGoogle}, saturazione x%.2f, canali %s".format(lut.saturazione, lut.canali.joinToString { "%.3f".format(it) })
            lut
        }
        val lutTono = tono?.lut
        val satGoogle = tono?.saturazione ?: 1f
        val canali = tono?.canali ?: floatArrayOf(1f, 1f, 1f)

        // ---------- rumore stimato ----------
        val prof = dng.profiloRumore
        val sS = prof?.let { p -> (p.indices step 2).map { p[it] }.average() } ?: 2e-5
        val sO = prof?.let { p -> (1 until p.size step 2).map { p[it] }.average() } ?: 1e-7
        val guadEsp = 2.0.pow(ev.toDouble())
        val xMid = GRIGIO / guadEsp
        val sigmaG = (1.16 * guadEsp * sqrt(max(sS * xMid + sO, 1e-12))).toFloat()
        par["rumore a grigio medio (gamma)"] = "%.4f".format(sigmaG)
        // più forte di prima ma applicata per intero solo nelle ombre (vedi riduciRumore): l'asfalto a grigio medio era più liscio di Google
        val epsLuma = (1.1f * sigmaG * stile.rumoreLuma).pow(2)
        val raggioCroma = when { sigmaG > 0.02f -> 6; sigmaG > 0.008f -> 4; else -> 2 }
        par["riduzione rumore"] = "luma eps %.2e, croma raggio %d".format(epsLuma, raggioCroma)

        // ---------- resa a piena risoluzione, a fasce ----------
        val cx = dng.ritaglio[0]; val cy = dng.ritaglio[1]; val cw = dng.ritaglio[2]; val ch = dng.ritaglio[3]
        val orient = dng.orientamento
        val ruotata = orient == 6 || orient == 8 || orient == 5 || orient == 7
        val outW = if (ruotata) ch else cw
        val outH = if (ruotata) cw else ch
        par["dimensione"] = "${outW}x$outH"
        par["orientamento"] = orient
        val gmW = (cw + 3) / 4
        val gmH = (ch + 3) / 4
        val gm = FloatArray(gmW * gmH)
        val margine = opz.margineHdr
        val esp = 2f.pow(ev)
        val margineRighe = 4 + 4 * raggioCroma + 12
        val fascia = max(64, min(256, 2_000_000 / W)) and 0x7FFFFFFE
        val sogliaSat = 0.95f
        var yOut = cy
        while (yOut < cy + ch) {
            val ya = yOut
            val yb = min(cy + ch, ya + fascia)
            val r0 = max(0, ya - margineRighe)
            val r1 = min(H, yb + margineRighe)
            val bh = r1 - r0
            val np = W * bh
            // 1) RAW lineare con obiettivo, bilanciamento ed esposizione
            val cfa = FloatArray(np)
            Filtri.perRighe(bh) { y ->
                val r = r0 + y
                for (c in 0 until W) cfa[y * W + c] = lineare(r, c, ombreggiatura(r, c)) * esp
            }
            // 2) demosaicizzazione (Hamilton-Adams)
            val R = FloatArray(np); val G = FloatArray(np); val B = FloatArray(np)
            demosaic(dng, cfa, W, bh, r0, R, G, B)
            // 3) luci bruciate verso il neutro, 4) colore verso Display P3
            // ogni canale satura a esposizione × bilanciamento: lì il colore non è più affidabile
            val lim = FloatArray(3) { esp * wb[it] }
            Filtri.perRighe(bh) { y ->
                for (i in y * W until (y + 1) * W) {
                    var r = R[i]; var g = G[i]; var b = B[i]
                    val q = max(r / lim[0], max(g / lim[1], b / lim[2]))
                    if (q > sogliaSat) {
                        val yy = (r + g + b) / 3f
                        val tt = ((q - sogliaSat) / (1f - sogliaSat)).coerceIn(0f, 1f)
                        r += (yy - r) * tt; g += (yy - g) * tt; b += (yy - b) * tt
                    }
                    R[i] = M[0][0] * r + M[0][1] * g + M[0][2] * b
                    G[i] = M[1][0] * r + M[1][1] * g + M[1][2] * b
                    B[i] = M[2][0] * r + M[2][1] * g + M[2][2] * b
                }
            }
            // 5) rumore: croma a metà risoluzione seguendo la luminanza, luma con filtro guidato leggero
            riduciRumore(R, G, B, W, bh, lr, lg, lb, epsLuma, raggioCroma, stile)
            // 6) luci e ombre per zone, curva, colore, nitidezza
            val out = IntArray(cw * (yb - ya))
            val gv = FloatArray(np) // valori in gamma per la nitidezza
            val yLin = FloatArray(np)
            val ingA = Filtri.Ingrandimento(coA, lw, lh, W, 1f / blocco, -0.5f)
            val ingB = Filtri.Ingrandimento(coB, lw, lh, W, 1f / blocco, -0.5f)
            Filtri.perRighe(bh) { y ->
                val r = r0 + y
                val fy = r.toFloat() / blocco - 0.5f
                val tmp = FloatArray(lw)
                val rigaA = FloatArray(W).also { ingA.riga(fy, tmp, it) }
                val rigaB = FloatArray(W).also { ingB.riga(fy, tmp, it) }
                for (c in 0 until W) {
                    val i = y * W + c
                    // filtro guidato veloce: coefficienti ingranditi, base calcolata sulla luminanza vera del pixel
                    val ys = max(lr * R[i] + lg * G[i] + lb * B[i], 1e-6f)
                    yLin[i] = ys
                    val ls = log2(ys)
                    val b0 = rigaA[c] * (ls - ev) + rigaB[c] + ev
                    val f = 2f.pow(guadagnoZona(b0, ls - b0))
                    var rr = max(R[i] * f, 0f); var gg = max(G[i] * f, 0f); var bb = max(B[i] * f, 0f)
                    val yy = lr * rr + lg * gg + lb * bb
                    val yd = curva(yy)
                    val s = if (yy > 1e-6f) yd / yy else 0f
                    rr *= s; gg *= s; bb *= s
                    if (stile.biancoNero) { rr = yd; gg = yd; bb = yd } else {
                        val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
                        val sat = if (mx > 1e-6f) (mx - mn) / mx else 0f
                        var kk = satGoogle * stile.saturazione * (1f + stile.vivacita * (1f - sat))
                        if (pelle(rr, gg, bb)) kk = 1f + (kk - 1f) * 0.4f
                        rr = yd + (rr - yd) * kk; gg = yd + (gg - yd) * kk; bb = yd + (bb - yd) * kk
                        // fuori gamma: verso il grigio della stessa luminanza
                        val mx2 = max(rr, max(gg, bb)); val mn2 = min(rr, min(gg, bb))
                        var tt = 1f
                        if (mx2 > 1f && mx2 - yd > 1e-6f) tt = min(tt, (1f - yd) / (mx2 - yd))
                        if (mn2 < 0f && yd - mn2 > 1e-6f) tt = min(tt, yd / (yd - mn2))
                        if (tt < 1f) { rr = yd + (rr - yd) * tt; gg = yd + (gg - yd) * tt; bb = yd + (bb - yd) * tt }
                    }
                    var r2 = s_curva(Colore.aSrgb(rr), stile.contrasto)
                    var g2 = s_curva(Colore.aSrgb(gg), stile.contrasto)
                    var b2 = s_curva(Colore.aSrgb(bb), stile.contrasto)
                    if (lutTono != null) {
                        r2 *= canali[0]; g2 *= canali[1]; b2 *= canali[2]
                        val l = lr * r2 + lg * g2 + lb * b2
                        val t = lutTono[(l * 255f).toInt().coerceIn(0, 255)] +
                            (lutTono[(l * 255f).toInt().coerceIn(0, 254) + 1] - lutTono[(l * 255f).toInt().coerceIn(0, 254)]) * ((l * 255f) - (l * 255f).toInt().coerceIn(0, 254))
                        if (l > 0.04f) { val q = t / l; r2 *= q; g2 *= q; b2 *= q } else { val d = t - l; r2 += d; g2 += d; b2 += d }
                    }
                    R[i] = r2; G[i] = g2; B[i] = b2
                    gv[i] = lr * R[i] + lg * G[i] + lb * B[i]
                }
            }
            // nitidezza sulla luminanza (in gamma), poi 8 bit con un filo di rumore anti-scalini
            val quanto = stile.nitidezza
            Filtri.perRighe(yb - ya) { yo ->
                val y = ya + yo - r0
                val r = ya + yo
                for (c in cx until cx + cw) {
                    val i = y * W + c
                    var d = 0f
                    if (quanto > 0f && c > 0 && c < W - 1 && y > 0 && y < bh - 1) {
                        val sfoc = (4 * gv[i] + 2 * (gv[i - 1] + gv[i + 1] + gv[i - W] + gv[i + W]) +
                            gv[i - W - 1] + gv[i - W + 1] + gv[i + W - 1] + gv[i + W + 1]) / 16f
                        d = gv[i] - sfoc
                        val soglia = 0.004f
                        d = if (abs(d) < soglia) d * (abs(d) / soglia) else d
                        d *= quanto * 1.6f
                        if (c > 1 && c < W - 2 && y > 1 && y < bh - 2) {
                            val medio = (gv[i - 2] + gv[i + 2] + gv[i - 2 * W] + gv[i + 2 * W]) / 4f
                            d += (sfoc - medio) * quanto * 0.6f
                        }
                        // nelle ombre la nitidezza accentuava i puntini di rumore (rapporti del 16/11/2026): lì si attenua
                        d *= 0.3f + 0.7f * ((gv[i] - 0.10f) / 0.20f).coerceIn(0f, 1f)
                        d = d.coerceIn(-0.12f, 0.12f) // niente aloni bianchi o neri sui bordi forti
                    }
                    var rr = R[i]; var gg = G[i]; var bb = B[i]
                    // alone viola (aberrazione cromatica) accanto alle luci forti: si toglie il colore, non la luce
                    // raggio 10 px: a 50 MP l'alone è largo (rapporti del 16/11/2026); anche il pixel stesso conta
                    if (min(rr, bb) - gg > 0.03f && bb >= gg && y >= 10 && y < bh - 10 && c >= 10 && c < W - 10) {
                        var mx = gv[i]
                        for (k in 2..10 step 2) mx = max(mx, max(max(gv[i - k], gv[i + k]), max(gv[i - k * W], gv[i + k * W])))
                        if (mx > 0.8f) {
                            val l = gv[i]
                            val q = (1f - ((mx - 0.8f) / 0.12f).coerceIn(0f, 1f) * 0.8f)
                            rr = l + (rr - l) * q; gg = l + (gg - l) * q; bb = l + (bb - l) * q
                        }
                    }
                    val rum = (((c * 73856093) xor (r * 19349663)) and 0xFF) / 255f - 0.5f
                    val a = ((rr + d) * 255f + rum).roundToInt().coerceIn(0, 255)
                    val b = ((gg + d) * 255f + rum).roundToInt().coerceIn(0, 255)
                    val e2 = ((bb + d) * 255f + rum).roundToInt().coerceIn(0, 255)
                    out[yo * cw + (c - cx)] = (0xFF shl 24) or (a shl 16) or (b shl 8) or e2
                }
            }
            // gain map (blocchi 4x4 della zona ritagliata), in log2
            for (gy in (ya - cy) / 4 until min(gmH, (yb - cy + 3) / 4)) {
                for (gx in 0 until gmW) {
                    var s = 0f; var cnt = 0
                    for (yy in gy * 4 + cy until min(gy * 4 + cy + 4, yb)) {
                        if (yy < ya) continue
                        for (xx in gx * 4 + cx until min(gx * 4 + cx + 4, cx + cw)) {
                            s += log2(rapportoHdr(yLin[(yy - r0) * W + xx], margine)); cnt++
                        }
                    }
                    if (cnt > 0) gm[gy * gmW + gx] += s / 16f
                }
            }
            consegna(out, cw, yb - ya, ya - cy, cw, ch, orient, scrivi)
            yOut = yb
        }
        tappa("resa")

        // gain map: 0..255 su log2(1)..log2(margine), ruotata come la foto
        val lmax = log2(margine)
        val byte = ByteArray(gmW * gmH) { (gm[it] / lmax * 255f).roundToInt().coerceIn(0, 255).toByte() }
        val (gbW, gbH, gb) = ruotaByte(byte, gmW, gmH, orient)
        par["gain map"] = "${gbW}x$gbH, fino a ${margine}x"
        return Esito(outW, outH, gb, gbW, gbH, margine, scena, stile.nome, par, tempi)
    }

    /**
     * Bianco dello scatto quando il DNG non lo dice: si cerca il neutro per cui il RAW, sviluppato,
     * ha lo stesso colore medio del JPEG di Google. Senza JPEG: media della scena grigia.
     */
    fun stimaNeutro(dng: Dng, rif: Riferimento?): DoubleArray {
        val acc = DoubleArray(3)
        val cnt = IntArray(3)
        val passo = max(2, (dng.larghezza / 512) and 0x7FFFFFFE)
        for (r in 0 until dng.altezza - 1 step passo) for (c in 0 until dng.larghezza - 1 step passo) {
            val v = DoubleArray(4) { pos ->
                val rr = r + (pos shr 1); val cc = c + (pos and 1)
                ((dng.campione(rr, cc) - dng.nero[pos]) / (dng.bianco - dng.nero[pos])).toDouble()
            }
            if (v.any { it > 0.9 } || v.all { it < 0.01 }) continue // luci bruciate e buio pesto non dicono il colore
            for (pos in 0..3) { val ch = dng.cfa[pos]; acc[ch] += v[pos]; cnt[ch]++ }
        }
        val m = DoubleArray(3) { acc[it] / max(1, cnt[it]) }
        if (m.any { it <= 0 }) return doubleArrayOf(1.0, 1.0, 1.0)
        if (rif == null) return DoubleArray(3) { m[it] / m[1] }
        val lut = DoubleArray(256) { Colore.daSrgb(it / 255f).toDouble() }
        val cg = DoubleArray(3)
        var k = 0
        for (p in rif.pixel) {
            val px = doubleArrayOf(lut[(p shr 16) and 0xFF], lut[(p shr 8) and 0xFF], lut[p and 0xFF])
            if (px.any { it > 0.95 } || px.all { it < 0.005 }) continue
            for (i in 0..2) cg[i] += px[i]
            k++
        }
        if (k == 0) return DoubleArray(3) { m[it] / m[1] }
        // P3 = M(n)·(cam/n)  ⇒  n = cam / (M⁻¹·colore di Google), a meno di una scala; si itera per la temperatura
        var n = doubleArrayOf(1.0, 1.0, 1.0)
        repeat(4) {
            val v = Colore.per(Colore.inversa(Colore.calcola(dng.colore, n).matrice), cg)
            if (v.any { it <= 0 }) return DoubleArray(3) { m[it] / m[1] }
            val nuovo = DoubleArray(3) { m[it] / v[it] }
            n = DoubleArray(3) { nuovo[it] / nuovo[1] }
        }
        return n
    }

    private fun s_curva(v: Float, k: Float): Float {
        if (k == 0f) return v
        val s = v * v * (3 - 2 * v)
        return v + (s - v) * k
    }

    /** Tonalità della pelle (arancio chiaro con saturazione moderata): si protegge dall'aumento di colore. */
    private fun pelle(r: Float, g: Float, b: Float): Boolean {
        if (!(r > g && g > b)) return false
        val h = (g - b) / (r - b + 1e-6f) // 0 = rosso, 1 = giallo
        val s = (r - b) / (r + 1e-6f)
        return h in 0.25f..0.75f && s in 0.15f..0.65f
    }

    /**
     * Curva del tono (in gamma) che porta la distribuzione delle luminanze della nostra resa su quella del JPEG
     * di Google, più il rapporto di saturazione. [resa] dà il colore lineare (display P3) del pixel i a bassa risoluzione.
     */
    fun curvaDaRiferimento(
        rf: Riferimento, n: Int, lr: Float, lg: Float, lb: Float, stile: Stile, resa: (Int) -> FloatArray,
    ): Tono {
        val nostra = DoubleArray(256)
        val mediaN = DoubleArray(3); val mediaG = DoubleArray(3)
        var satN = 0.0; var cntN = 0
        for (i in 0 until n) {
            val c = resa(i)
            val r = s_curva(Colore.aSrgb(c[0]), stile.contrasto)
            val g = s_curva(Colore.aSrgb(c[1]), stile.contrasto)
            val b = s_curva(Colore.aSrgb(c[2]), stile.contrasto)
            val l = lr * r + lg * g + lb * b
            nostra[(l * 255).toInt().coerceIn(0, 255)]++
            if (l in 0.2f..0.85f) { satN += (max(r, max(g, b)) - min(r, min(g, b))).toDouble(); cntN++; mediaN[0] += r.toDouble(); mediaN[1] += g.toDouble(); mediaN[2] += b.toDouble() }
        }
        val google = DoubleArray(256)
        var satG = 0.0; var cntG = 0
        for (p in rf.pixel) {
            val r = ((p shr 16) and 0xFF) / 255f; val g = ((p shr 8) and 0xFF) / 255f; val b = (p and 0xFF) / 255f
            val l = lr * r + lg * g + lb * b
            google[(l * 255).toInt().coerceIn(0, 255)]++
            if (l in 0.2f..0.85f) { satG += (max(r, max(g, b)) - min(r, min(g, b))).toDouble(); cntG++; mediaG[0] += r.toDouble(); mediaG[1] += g.toDouble(); mediaG[2] += b.toDouble() }
        }
        fun cdf(h: DoubleArray): DoubleArray { val t = h.sum(); var s = 0.0; return DoubleArray(256) { s += h[it]; s / t } }
        val cN = cdf(nostra); val cG = cdf(google)
        // per ogni livello nostro, il livello di Google con la stessa percentuale di pixel più scuri
        val grezza = FloatArray(256) { v ->
            var j = 0
            while (j < 255 && cG[j] < cN[v]) j++
            j / 255f
        }
        // levigata, sempre crescente, pendenza contenuta, e mescolata con la nostra secondo lo stile
        val lev = FloatArray(256) { v -> (-4..4).map { grezza[(v + it).coerceIn(0, 255)] }.average().toFloat() }
        val lut = FloatArray(256)
        var prima = 0f
        for (v in 0..255) {
            val id = v / 255f
            val m = id + (lev[v] - id) * stile.tonoGoogle
            val lim = if (v == 0) m else m.coerceIn(prima + 0.3f / 255f, prima + 3f / 255f)
            lut[v] = lim.coerceIn(0f, 1f); prima = lut[v]
        }
        val sat = if (cntN > 50 && cntG > 50 && satN > 0) ((satG / cntG) / (satN / cntN)).toFloat().coerceIn(0.85f, 1.6f) else 1f
        // dominante: rapporto per canale delle medie dei toni medi, a luminanza invariata
        val canali = FloatArray(3) { 1f }
        if (cntN > 50 && cntG > 50) {
            val gN = mediaN.map { it / cntN }; val gG = mediaG.map { it / cntG }
            val lN = lr * gN[0] + lg * gN[1] + lb * gN[2]; val lG = lr * gG[0] + lg * gG[1] + lb * gG[2]
            for (c in 0..2) if (gN[c] > 0.01 && lG > 0.01) {
                val k = ((gG[c] / lG) / (gN[c] / lN)).toFloat().coerceIn(0.85f, 1.18f)
                canali[c] = 1f + (k - 1f) * stile.tonoGoogle
            }
        }
        return Tono(lut, 1f + (sat - 1f) * stile.tonoGoogle, canali)
    }

    class Tono(val lut: FloatArray, val saturazione: Float, val canali: FloatArray)

    /** Mediana della luminanza lineare del JPEG di riferimento. */
    private fun medianaRiferimento(rf: Riferimento, lr: Float, lg: Float, lb: Float): Float {
        val lut = FloatArray(256) { Colore.daSrgb(it / 255f) }
        val isto = IntArray(1024)
        for (p in rf.pixel) {
            val y = lr * lut[(p shr 16) and 0xFF] + lg * lut[(p shr 8) and 0xFF] + lb * lut[p and 0xFF]
            isto[(y * 1023).toInt().coerceIn(0, 1023)]++
        }
        var s = 0
        for (j in 0 until 1024) { s += isto[j]; if (s >= rf.pixel.size / 2) return j / 1023f }
        return GRIGIO
    }

    /** Demosaicizzazione Hamilton-Adams: verde lungo la direzione più liscia, poi rosso e blu per differenza di colore. */
    private fun demosaic(dng: Dng, v: FloatArray, w: Int, h: Int, r0: Int, R: FloatArray, G: FloatArray, B: FloatArray) {
        fun col(y: Int, x: Int) = dng.coloreIn(r0 + y, x)
        fun at(y: Int, x: Int) = v[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        // verde ovunque
        Filtri.perRighe(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                if (col(y, x) == 1) { G[i] = v[i]; continue }
                val c = v[i]
                val gl = at(y, x - 1); val gr = at(y, x + 1); val gu = at(y - 1, x); val gd = at(y + 1, x)
                val dh = abs(gl - gr) + abs(2 * c - at(y, x - 2) - at(y, x + 2))
                val dv = abs(gu - gd) + abs(2 * c - at(y - 2, x) - at(y + 2, x))
                val oh = (gl + gr) / 2 + (2 * c - at(y, x - 2) - at(y, x + 2)) / 4
                val ov = (gu + gd) / 2 + (2 * c - at(y - 2, x) - at(y + 2, x)) / 4
                var g = when {
                    dh < dv -> oh
                    dv < dh -> ov
                    else -> (oh + ov) / 2
                }
                val lo = min(min(gl, gr), min(gu, gd)); val hi = max(max(gl, gr), max(gu, gd))
                g = g.coerceIn(lo - (hi - lo) * 0.5f, hi + (hi - lo) * 0.5f)
                G[i] = max(g, 0f)
            }
        }
        fun gAt(y: Int, x: Int) = G[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        fun diff(y: Int, x: Int) = at(y, x) - gAt(y, x)
        Filtri.perRighe(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = col(y, x)
                val g = G[i]
                val ds = FloatArray(3)
                for (t in 0..2) {
                    if (t == 1) continue
                    ds[t] = when {
                        c == t -> v[i] - g
                        c == 1 -> {
                            // sito verde: il colore t sta a sinistra/destra oppure sopra/sotto
                            if (col(y, (x + 1).coerceAtMost(w - 1)) == t || col(y, (x - 1).coerceAtLeast(0)) == t)
                                (diff(y, x - 1) + diff(y, x + 1)) / 2
                            else (diff(y - 1, x) + diff(y + 1, x)) / 2
                        }
                        else -> (diff(y - 1, x - 1) + diff(y - 1, x + 1) + diff(y + 1, x - 1) + diff(y + 1, x + 1)) / 4
                    }
                }
                R[i] = max(g + ds[0], 0f)
                B[i] = max(g + ds[2], 0f)
            }
        }
    }

    private fun riduciRumore(
        R: FloatArray, G: FloatArray, B: FloatArray, w: Int, h: Int, lr: Float, lg: Float, lb: Float,
        epsLuma: Float, raggio: Int, stile: Stile,
    ) {
        if (stile.rumoreCroma > 0f) {
            val hw = w / 2; val hh = h / 2
            val gy = FloatArray(hw * hh); val c1 = FloatArray(hw * hh); val c2 = FloatArray(hw * hh)
            Filtri.perRighe(hh) { y ->
                for (x in 0 until hw) {
                    var sy = 0f; var s1 = 0f; var s2 = 0f
                    for (dy in 0..1) for (dx in 0..1) {
                        val i = (2 * y + dy) * w + 2 * x + dx
                        val yy = lr * R[i] + lg * G[i] + lb * B[i]
                        sy += yy; s1 += R[i] - yy; s2 += B[i] - yy
                    }
                    val j = y * hw + x
                    gy[j] = sqrt(max(sy / 4, 0f)); c1[j] = s1 / 4; c2[j] = s2 / 4
                }
            }
            val f1 = FloatArray(hw * hh); val f2 = FloatArray(hw * hh)
            Filtri.guidato(gy, c1, hw, hh, raggio, 0.0006f, f1)
            Filtri.guidato(gy, c2, hw, hh, raggio, 0.0006f, f2)
            // nelle ombre la guida è anch'essa rumore e lascia puntini colorati (rapporti del 16/11/2026): lì media semplice più larga
            val tmpM = FloatArray(hw * hh); val b1 = FloatArray(hw * hh); val b2 = FloatArray(hw * hh)
            Filtri.media(c1, hw, hh, 2 * raggio, tmpM, b1)
            Filtri.media(c2, hw, hh, 2 * raggio, tmpM, b2)
            Filtri.perRighe(hh) { y ->
                for (j in y * hw until (y + 1) * hw) {
                    val t = ((0.30f - gy[j]) / 0.20f).coerceIn(0f, 1f)
                    f1[j] += (b1[j] - f1[j]) * t; f2[j] += (b2[j] - f2[j]) * t
                }
            }
            val forza = stile.rumoreCroma.coerceIn(0f, 1f)
            val ing1 = Filtri.Ingrandimento(f1, hw, hh, w, 0.5f, -0.25f)
            val ing2 = Filtri.Ingrandimento(f2, hw, hh, w, 0.5f, -0.25f)
            Filtri.perRighe(h) { y ->
                val fy = y / 2f - 0.25f
                val tmp = FloatArray(hw)
                val riga1 = FloatArray(w).also { ing1.riga(fy, tmp, it) }
                val riga2 = FloatArray(w).also { ing2.riga(fy, tmp, it) }
                for (x in 0 until w) {
                    val i = y * w + x
                    val yy = lr * R[i] + lg * G[i] + lb * B[i]
                    val o1 = R[i] - yy; val o2 = B[i] - yy
                    // il dettaglio fine di crominanza (sotto i 2 pixel) è quasi tutto rumore: si usa quella filtrata
                    val m1 = riga1[x]; val m2 = riga2[x]
                    val d1 = o1 + (m1 - o1) * forza
                    val d2 = o2 + (m2 - o2) * forza
                    val r = yy + d1; val b = yy + d2
                    R[i] = r; B[i] = b
                    G[i] = (yy - lr * r - lb * b) / lg
                }
            }
        }
        if (epsLuma > 0f) {
            val n = w * h
            val yg = FloatArray(n)
            Filtri.perRighe(h) { y -> for (i in y * w until (y + 1) * w) yg[i] = Colore.aSrgb(max(lr * R[i] + lg * G[i] + lb * B[i], 0f)) }
            val f = FloatArray(n)
            Filtri.guidato(yg, yg, w, h, 1, epsLuma, f)
            Filtri.perRighe(h) { y ->
                for (i in y * w until (y + 1) * w) {
                    if (yg[i] <= 1e-5f) continue
                    // piena nelle ombre, un terzo nei toni medi e chiari, dove il rumore si vede poco e la trama sì
                    val peso = 1f - 0.65f * ((yg[i] - 0.2f) / 0.3f).coerceIn(0f, 1f)
                    val fy = yg[i] + (f[i] - yg[i]) * peso
                    val s = Colore.daSrgb(fy.coerceIn(0f, 1f)) / max(Colore.daSrgb(yg[i]), 1e-7f)
                    R[i] *= s; G[i] *= s; B[i] *= s
                }
            }
        }
    }

    /** Consegna una fascia già ruotata secondo l'orientamento EXIF (1, 3, 6, 8; gli specchiati come i ruotati). */
    private fun consegna(px: IntArray, w: Int, h: Int, y0: Int, cw: Int, ch: Int, orient: Int, scrivi: (Int, Int, Int, Int, IntArray) -> Unit) {
        when (orient) {
            3 -> {
                val o = IntArray(w * h) { i -> px[w * h - 1 - i] }
                scrivi(0, ch - y0 - h, w, h, o)
            }
            6, 5 -> {
                // 90° in senso orario: (x, y) → (ch-1-y, x)
                val o = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = px[y * w + x]
                scrivi(ch - y0 - h, 0, h, w, o)
            }
            8, 7 -> {
                // 90° in senso antiorario: (x, y) → (y, cw-1-x)
                val o = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) o[(cw - 1 - x) * h + y] = px[y * w + x]
                scrivi(y0, 0, h, w, o)
            }
            else -> scrivi(0, y0, w, h, px)
        }
    }

    private fun ruotaByte(b: ByteArray, w: Int, h: Int, orient: Int): Triple<Int, Int, ByteArray> = when (orient) {
        3 -> Triple(w, h, ByteArray(w * h) { b[w * h - 1 - it] })
        6, 5 -> Triple(h, w, ByteArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = b[y * w + x] })
        8, 7 -> Triple(h, w, ByteArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = b[y * w + x] })
        else -> Triple(w, h, b)
    }
}
