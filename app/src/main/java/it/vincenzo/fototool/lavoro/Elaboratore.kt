package it.vincenzo.fototool.lavoro

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import it.vincenzo.fototool.BuildConfig
import it.vincenzo.fototool.core.Gusti
import it.vincenzo.fototool.core.Reports
import it.vincenzo.fototool.motore.Dng
import it.vincenzo.fototool.motore.Opzioni
import it.vincenzo.fototool.motore.Riferimento
import it.vincenzo.fototool.motore.Stile
import it.vincenzo.fototool.motore.Sviluppo
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Elabora un DNG di Pixel Camera: sviluppo con il motore, gain map Ultra HDR, salvataggio in DCIM/FotoTool
 * con i dati EXIF del JPEG di Google, miniature e rapporto completo. L'originale non viene mai toccato.
 */
object Elaboratore {
    /** Nome della foto in elaborazione, per la schermata. */
    val inCorso = MutableStateFlow<String?>(null)

    private const val CARTELLA = "DCIM/FotoTool"

    @Synchronized
    fun elabora(ctx: Context, foto: Foto, stileScelto: Stile?): Archivio.Voce {
        val d = JSONObject()
        val tempi = JSONObject()
        var t = SystemClock.elapsedRealtime()
        val t0 = t
        fun tappa(n: String) { val o = SystemClock.elapsedRealtime(); tempi.put(n, o - t); t = o }
        inCorso.value = foto.nome
        Servizio.aggiorna(ctx, foto.nome)
        Reports.log("Elaboro ${foto.nome} (${foto.dimensione shr 20} MB, ${foto.larghezza}x${foto.altezza})")
        d.put("foto", foto.nome).put("dimensioneDng", "${foto.dimensione shr 20} MB").put("stileRichiesto", stileScelto?.nome ?: "dai gusti")
        try {
            val buf = mappa(ctx, foto.uri)
            tappa("apertura")
            val dng = Dng.leggi(buf)
            tappa("lettura DNG")
            d.put("dng", JSONObject(dng.note as Map<*, *>))
            d.put("exif", JSONObject().put("modello", dng.exif.modello).put("software", dng.exif.software)
                .put("tempo", dng.exif.tempo).put("diaframma", dng.exif.diaframma).put("iso", dng.exif.iso).put("focale", dng.exif.focale))

            val gemello = Galleria.gemello(ctx, foto)
            d.put("gemello", gemello?.let { "${it.nome.removePrefix(foto.base)} ${it.larghezza}x${it.altezza}" } ?: "non trovato")
            val rif = gemello?.let { runCatching { riferimento(ctx, it.uri) }.getOrNull() }
            tappa("riferimento Google")

            val (w, h) = Sviluppo.dimensioni(dng)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val opz = Opzioni(
                stile = stileScelto ?: it.vincenzo.fototool.motore.Stili.NATURALE,
                riferimento = rif,
                stilePerScena = if (stileScelto == null) { s -> Gusti.stilePer(ctx, s) } else null,
            )
            val esito = Sviluppo.sviluppa(dng, opz) { x, y, bw, bh, px -> bmp.setPixels(px, 0, bw, x, y, bw, bh) }
            tappa("sviluppo")
            d.put("parametri", JSONObject(esito.parametri as Map<*, *>)).put("tempiMotore", JSONObject(esito.tempi as Map<*, *>))
            // i pixel sono già in Display P3 (curva sRGB): si cambia solo l'etichetta, senza convertire
            bmp.setColorSpace(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
            bmp.gainmap = gainmap(esito.gainMap, esito.gainLarghezza, esito.gainAltezza, esito.rapportoMax)

            val nome = "${foto.base}_FotoTool.jpg"
            val uri = salva(ctx, bmp, nome, foto, gemello)
            tappa("salvataggio")
            val ultra = verifica(ctx, uri)
            d.put("ultraHdr", ultra)
            tappa("verifica")

            // miniature per la schermata e per il rapporto
            val dopo = scala(bmp, 384)
            // ritagli al 100% (nostra e Google, stessa zona) per giudicare dettaglio e rumore dai rapporti
            runCatching { d.put("ritagli", ritagli(ctx, bmp, dopo, gemello)) }.onFailure { d.put("ritagli", "errore: ${it.message}") }
            bmp.recycle()
            val prima = gemello?.let { runCatching { BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(it.uri), null,
                BitmapFactory.Options().apply { inSampleSize = campionamento(it.larghezza, it.altezza, 384) }) }.getOrNull() }
                ?.let { scala(it, 384) }
            Archivio.miniatura(ctx, foto.id, "dopo").outputStream().use { dopo.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            prima?.let { p -> Archivio.miniatura(ctx, foto.id, "prima").outputStream().use { p.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
            d.put("miniature", JSONObject().put("dopo", b64(dopo)).put("prima", prima?.let { b64(it) } ?: ""))
            tappa("miniature")

            val voce = Archivio.Voce(
                id = foto.id, nome = foto.nome, uscita = uri.toString(), originale = gemello?.uri?.toString(),
                stile = esito.stile, scena = esito.scena, quando = System.currentTimeMillis(),
                ms = SystemClock.elapsedRealtime() - t0, dimensione = "${esito.larghezza}x${esito.altezza}", ultraHdr = ultra,
            )
            Archivio.salva(ctx, voce)
            d.put("tempi", tempi).put("totaleMs", voce.ms)
            d.put("memoria", "java ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20} MB, " +
                "nativa ${Debug.getNativeHeapAllocatedSize() shr 20} MB, massimo java ${Runtime.getRuntime().maxMemory() shr 20} MB")
            d.put("gusti", JSONObject(Gusti.riepilogo(ctx) as Map<*, *>))
            Reports.log("Fatto ${foto.nome}: ${esito.stile}, scena ${esito.scena}, ${voce.ms} ms, Ultra HDR $ultra")
            Reports.foto(d)
            return voce
        } catch (e: Throwable) {
            d.put("tempi", tempi)
            d.put("memoria", "java ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20} MB, " +
                "nativa ${Debug.getNativeHeapAllocatedSize() shr 20} MB")
            Reports.log("Errore su ${foto.nome}: ${e.javaClass.simpleName}: ${e.message}")
            Reports.foto(d, e)
            throw e
        } finally {
            inCorso.value = null
            Servizio.aggiorna(ctx, null)
        }
    }

    /** Il DNG mappato in memoria (niente copia da 48 MB); se il sistema non lo permette, letto tutto. */
    private fun mappa(ctx: Context, uri: Uri): ByteBuffer {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("impossibile aprire il DNG")
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                return runCatching { ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) as ByteBuffer }.getOrElse { _ ->
                    val b = ByteBuffer.allocate(ch.size().toInt())
                    while (b.hasRemaining() && ch.read(b, b.position().toLong()) > 0) Unit
                    b.flip()
                    b
                }
            }
        }
    }

    private fun campionamento(w: Int, h: Int, lato: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= lato) s *= 2
        return s
    }

    /** Il JPEG di Google a circa 1000 pixel di lato, per prendere la sua esposizione come riferimento. */
    private fun riferimento(ctx: Context, uri: Uri): Riferimento? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        val b = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = campionamento(o.outWidth, o.outHeight, 1000)
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null
        // getPixels convertirebbe in sRGB: si copiano i byte così come sono (Display P3, come la nostra resa)
        val buf = ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(buf)
        val by = buf.array()
        val passo = b.rowBytes
        val px = IntArray(b.width * b.height) { i ->
            val o = (i / b.width) * passo + (i % b.width) * 4 // ARGB_8888 in memoria: R, G, B, A
            (0xFF shl 24) or ((by[o].toInt() and 0xFF) shl 16) or ((by[o + 1].toInt() and 0xFF) shl 8) or (by[o + 2].toInt() and 0xFF)
        }
        return Riferimento(b.width, b.height, px).also { b.recycle() }
    }

    /** Gain map Ultra HDR: un canale, valori lineari in log2 tra 1x e il rapporto massimo. */
    private fun gainmap(dati: ByteArray, w: Int, h: Int, massimo: Float): Gainmap {
        val g = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val riga = g.rowBytes
        val buf = ByteBuffer.allocate(riga * h)
        for (y in 0 until h) { buf.position(y * riga); buf.put(dati, y * w, w) }
        buf.rewind()
        g.copyPixelsFromBuffer(buf)
        return Gainmap(g).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(massimo, massimo, massimo)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setDisplayRatioForFullHdr(massimo)
            setMinDisplayRatioForHdrTransition(1f)
        }
    }

    /** Salva in DCIM/FotoTool (sostituendo una versione precedente fatta da FotoTool) e copia i dati EXIF di Google. */
    private fun salva(ctx: Context, bmp: Bitmap, nome: String, foto: Foto, gemello: Foto?): Uri {
        val cr = ctx.contentResolver
        val raccolta = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        cr.query(raccolta, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=?",
            arrayOf(nome, ctx.packageName), null)?.use { c ->
            while (c.moveToNext()) runCatching { cr.delete(android.content.ContentUris.withAppendedId(raccolta, c.getLong(0)), null, null) }
        }
        val valori = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, CARTELLA)
            if (foto.data > 0) put(MediaStore.Images.Media.DATE_TAKEN, foto.data)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(raccolta, valori) ?: error("impossibile creare la foto in $CARTELLA")
        try {
            cr.openOutputStream(uri)?.use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) error("compressione JPEG non riuscita") }
                ?: error("impossibile scrivere la foto")
            runCatching { copiaExif(ctx, gemello, uri) }.onFailure { Reports.log("EXIF non copiati: ${it.message}") }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }

    private val TAG_COPIATI = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** I dati dello scatto (e la posizione, se il permesso c'è) passano dalla foto di Google alla nostra. */
    private fun copiaExif(ctx: Context, gemello: Foto?, uri: Uri) {
        val sorgente = gemello?.let {
            val u = runCatching { MediaStore.setRequireOriginal(it.uri) }.getOrDefault(it.uri)
            runCatching { ctx.contentResolver.openInputStream(u) }.getOrNull() ?: ctx.contentResolver.openInputStream(it.uri)
        }?.use { ExifInterface(it) }
        ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val ex = ExifInterface(pfd.fileDescriptor)
            if (sorgente != null) for (tag in TAG_COPIATI) sorgente.getAttribute(tag)?.let { ex.setAttribute(tag, it) }
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "FotoTool ${BuildConfig.VERSION_NAME}")
            ex.saveAttributes()
        }
    }

    /** Controlla che la foto salvata si apra e abbia davvero la gain map (Ultra HDR). */
    private fun verifica(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 })
        }?.let { b -> b.hasGainmap().also { b.recycle() } } ?: false
    }.getOrDefault(false)

    private const val LATO_RITAGLIO = 512

    /**
     * Due ritagli a piena risoluzione: il centro e la zona più ricca di dettaglio (scelta sulla miniatura).
     * Per ognuno la nostra versione e quella di Google nella stessa zona.
     */
    private fun ritagli(ctx: Context, bmp: Bitmap, mini: Bitmap, gemello: Foto?): org.json.JSONArray {
        // zona con più dettaglio: griglia 4x4 sulla miniatura, energia del gradiente di luminanza
        val mw = mini.width; val mh = mini.height
        val px = IntArray(mw * mh).also { mini.getPixels(it, 0, mw, 0, 0, mw, mh) }
        fun l(x: Int, y: Int) = px[y * mw + x].let { ((it shr 16) and 255) * 3 + ((it shr 8) and 255) * 6 + (it and 255) }
        var migliore = 0 to 0; var energia = -1L
        for (gy in 0 until 4) for (gx in 0 until 4) {
            var e = 0L
            for (y in gy * mh / 4 until (gy + 1) * mh / 4 - 1) for (x in gx * mw / 4 until (gx + 1) * mw / 4 - 1)
                e += kotlin.math.abs(l(x + 1, y) - l(x, y)) + kotlin.math.abs(l(x, y + 1) - l(x, y))
            // le 4 celle centrali le copre già il ritaglio "centro"
            if (e > energia && !(gx in 1..2 && gy in 1..2)) { energia = e; migliore = gx to gy }
        }
        val zone = listOf(
            "centro" to (0.5f to 0.5f),
            "dettaglio" to ((migliore.first + 0.5f) / 4f to (migliore.second + 0.5f) / 4f),
        )
        val dec = gemello?.let { g -> runCatching { ctx.contentResolver.openInputStream(g.uri)?.use { android.graphics.BitmapRegionDecoder.newInstance(it) } }.getOrNull() }
        val out = org.json.JSONArray()
        try {
            for ((nome, c) in zone) {
                val lato = minOf(LATO_RITAGLIO, bmp.width, bmp.height)
                val x = (c.first * bmp.width - lato / 2).toInt().coerceIn(0, bmp.width - lato)
                val y = (c.second * bmp.height - lato / 2).toInt().coerceIn(0, bmp.height - lato)
                val nostro = Bitmap.createBitmap(bmp, x, y, lato, lato)
                val o = JSONObject().put("zona", nome).put("x", x).put("y", y).put("lato", lato).put("dopo", b64(nostro, 88))
                nostro.recycle()
                if (dec != null) {
                    // stessa zona nel JPEG di Google (le dimensioni possono differire di qualche pixel per il ritaglio)
                    val sx = dec.width.toFloat() / bmp.width; val sy = dec.height.toFloat() / bmp.height
                    val gx = (x * sx).toInt().coerceIn(0, maxOf(0, dec.width - lato)); val gy = (y * sy).toInt().coerceIn(0, maxOf(0, dec.height - lato))
                    dec.decodeRegion(android.graphics.Rect(gx, gy, gx + lato, gy + lato), null)?.let { o.put("prima", b64(it, 88)); it.recycle() }
                }
                out.put(o)
            }
        } finally { dec?.recycle() }
        return out
    }

    private fun scala(b: Bitmap, lato: Int): Bitmap {
        val s = lato.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun b64(b: Bitmap, qualita: Int = 70): String {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, qualita, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }
}
