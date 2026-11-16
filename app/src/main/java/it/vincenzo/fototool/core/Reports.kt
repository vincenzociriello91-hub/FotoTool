package it.vincenzo.fototool.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.util.Base64
import it.vincenzo.fototool.BuildConfig
import it.vincenzo.fototool.lavoro.Archivio
import it.vincenzo.fototool.lavoro.Coda
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Rapporti completi ("vita, morte e miracoli") di ogni utilizzo e di ogni foto elaborata.
 * - Ogni evento finisce nel registro, scritto anche su disco: se Android chiude l'app senza avvisare,
 *   al riavvio il rapporto "chiusura-improvvisa" recupera tutto.
 * - Ogni rapporto: copia leggibile in Download/FotoTool_report e copia cifrata (RSA-OAEP-256 + AES-256-GCM)
 *   in coda per il ricevitore rapporti-vincenzo, che la salva in FotoTool-rapporti. Senza rete resta in coda.
 * Le miniature viaggiano solo dentro la parte cifrata; mai posizione GPS né altri dati personali.
 * (Schema ripreso da TVPilot.)
 */
object Reports {
    const val FOLDER = "FotoTool_report"
    const val URL = "https://rapporti-vincenzo.vincenzociriello91.workers.dev"
    private const val APP = "FotoTool"

    /** Chiave pubblica di FotoTool: la privata è su Drive, FOTOTOOL_RAPPORTI_CHIAVE.pem */
    private const val PUB_N = "mv9dfG4RJ2v6TZUQ6GmNKCZEDttafI2L-VJpyjBM-wjq6NLAjUD1iaPV98mRtKQRU9qWvR5Ec6XgpJSsi9l-3xDfDDPHT9T5lm_3B5BfcJ8OQNHveZIwexRqKxRl4RTNfmNPJUTU76Hkrrv9QCSjWCqzOOMPuD91mc-zLMx3GbHMQ1ln8Oiesot-CfPDkG5VKYExSE7EUH-8lPd_WRuhMylfu9JyPDDD60NO3I9W6kBi5cD23s41-jf0sfoOZsdHtMVbKcAiQx86kY10gWKDQU4LgVBQplpT37JlJqBHt__yvGfsrrKPhZm-_9q_fvMVgSTKGt86moOa798sWLKETxnNKoi1WZV_z_A9iEQpKnfPRgNy7WSnP3psvGcdCpO6tCJNWDiD6-pkDyheM2bAm575ymog1ZGAZxEeO5h_csNVNLf9OeK_SPMqUZJDyxAGiBxzWJWINwQXVBp7dYvePixovbeQjIsgmp6FtyUQ9u0-qK3KwFpQAyUNbuZ821yt"
    private const val PUB_E = "AQAB"

    private val events = mutableListOf<Pair<Long, String>>()
    private var start = System.currentTimeMillis()
    private var app: Context? = null
    private var journal: File? = null
    private var lastMsg: String? = null
    private var repeats = 0

    /** Esito dell'ultimo invio, mostrato nella scheda Info */
    @Volatile var lastSend: String = "nessun invio ancora"

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        journal = File(ctx.filesDir, "eventi-in-corso.txt")
        recoverPrevious(ctx)
    }

    private fun closeRepeats() {
        if (repeats == 0) return
        val note = "(il messaggio precedente si è ripetuto altre $repeats volte)"
        repeats = 0
        val t = System.currentTimeMillis()
        events += t to note
        runCatching { journal?.appendText("$t\t$note\n") }
    }

    fun log(msg: String) {
        synchronized(events) {
            if (msg == lastMsg) { repeats++; return }
            closeRepeats()
            lastMsg = msg
            val t = System.currentTimeMillis()
            events += t to msg
            if (events.size > 3000) events.removeAt(0)
            runCatching { journal?.appendText("$t\t${msg.replace('\n', ' ')}\n") }
        }
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY).format(Date(t))

    /** Fotografia dello stato del telefono e dell'app al momento del rapporto */
    private fun snapshot(): JSONObject {
        val o = JSONObject()
        val ctx = app ?: return o
        fun put(k: String, v: () -> Any?) {
            runCatching { o.put(k, v() ?: JSONObject.NULL) }.onFailure { o.put(k, "errore: ${it.message}") }
        }
        put("fotoElaborate") { Archivio.voci(ctx).size }
        put("inCoda") { Coda.attese(ctx).size }
        put("permessoFoto") { ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED }
        put("pixelCamera") { runCatching { ctx.packageManager.getPackageInfo("com.google.android.GoogleCamera", 0).versionName }.getOrNull() }
        put("osservatoreProgrammato") { it.vincenzo.fototool.lavoro.Osservatore.programmato(ctx) }
        put("memoriaApp") { "${Runtime.getRuntime().maxMemory() shr 20} MB" }
        put("spazioLibero") { StatFs(Environment.getExternalStorageDirectory().path).let { "${it.availableBytes shr 30} GB" } }
        put("batteria") {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)?.let {
                "${it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}%, ${it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} °C"
            }
        }
        put("statoTermico") { ctx.getSystemService(PowerManager::class.java).currentThermalStatus }
        put("rapportiInCoda") { queueDir(ctx).listFiles()?.size ?: 0 }
        return o
    }

    private fun build(
        reason: String, error: Throwable? = null, exit: JSONObject? = null, withState: Boolean = true, dettagli: JSONObject? = null,
    ): JSONObject {
        val state = if (!withState) JSONObject() else runCatching { snapshot() }.getOrElse { JSONObject().put("errore", it.message) }
        return synchronized(events) {
            closeRepeats()
            lastMsg = null
            val ev = JSONArray()
            events.forEach { (t, m) -> ev.put(JSONObject().put("ora", iso(t)).put("msg", m)) }
            val json = JSONObject()
                .put("app", APP)
                .put("versione", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.SDK_INT)
                .put("build", Build.DISPLAY)
                .put("motivo", reason)
                .put("inizio", iso(events.firstOrNull()?.first ?: start))
                .put("fine", iso(events.lastOrNull()?.first?.takeIf { exit != null } ?: System.currentTimeMillis()))
                .put("eventi", ev)
                .put("stato", state)
            if (dettagli != null) json.put("dettagli", dettagli)
            if (error != null) json.put("errore", error.stackTraceToString())
            if (exit != null) json.put("uscita", exit)
            events.clear()
            runCatching { journal?.writeText("") }
            start = System.currentTimeMillis()
            json
        }
    }

    private fun queueDir(ctx: Context) = File(ctx.filesDir, "rapporti-coda").apply { mkdirs() }

    /** Copia leggibile sul telefono (senza miniature) + copia cifrata completa in coda per l'invio */
    private fun save(json: JSONObject, reason: String) {
        val ctx = app ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ITALY).format(Date())
        runCatching {
            val leggibile = JSONObject(json.toString())
            leggibile.optJSONObject("dettagli")?.remove("miniature")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$stamp-$reason.json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(leggibile.toString(2).toByteArray()) }
            }
            pruneLocal(ctx)
        }
        runCatching { File(queueDir(ctx), "$stamp-$reason.json").writeText(encrypt(json.toString()).toString()) }
    }

    /** Tiene solo le ultime 60 copie leggibili in Download/FotoTool_report */
    private fun pruneLocal(ctx: Context, keep: Int = 60) {
        val uris = mutableListOf<android.net.Uri>()
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("%$FOLDER%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) uris += android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        uris.drop(keep).forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }

    /** Cifratura compatibile con WebCrypto: AES-GCM 256 (tag in coda) + chiave AES cifrata RSA-OAEP SHA-256 */
    fun encrypt(text: String): JSONObject {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv)) }
        val data = gcm.doFinal(text.toByteArray())
        val n = BigInteger(1, Base64.decode(PUB_N, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        val e = BigInteger(1, Base64.decode(PUB_E, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }
        val key = rsa.doFinal(aes.encoded)
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("app", APP).put("v", BuildConfig.VERSION_NAME).put("alg", "RSA-OAEP-256+A256GCM")
            .put("key", b64(key)).put("iv", b64(iv)).put("data", b64(data))
    }

    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Invia la coda al ricevitore (fuori dal thread principale), a lotti sotto il limite di 1 MB del ricevitore */
    fun sendQueue() {
        if (!sending.compareAndSet(false, true)) return
        try { sendQueueNow() } finally { sending.set(false) }
    }

    private fun sendQueueNow() {
        val ctx = app ?: return
        val files = queueDir(ctx).listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        var inviati = 0
        var esito = ""
        var lotto = mutableListOf<File>()
        var dim = 0L
        fun invia(): Boolean {
            if (lotto.isEmpty()) return true
            val arr = JSONArray()
            lotto.forEach { f -> runCatching { arr.put(JSONObject(f.readText())) }.onFailure { f.delete() } }
            val r = runCatching { Http.post("$URL/r/$APP", arr.toString()) }
            val ok = r.getOrNull()?.first in 200..299
            if (ok) { lotto.forEach { it.delete() }; inviati += lotto.size }
            else esito = r.fold({ (c, b) -> "errore $c ${b.take(120)}" }, { "niente rete (${it.message}), restano in coda" })
            lotto = mutableListOf(); dim = 0
            return ok
        }
        for (f in files) {
            val l = f.length()
            if (l > 900_000) { f.delete(); log("Rapporto troppo grande scartato: ${f.name} ($l byte)"); continue }
            if (dim + l > 800_000 || lotto.size >= 40) if (!invia()) break
            lotto += f; dim += l
        }
        if (esito.isEmpty()) invia()
        lastSend = "${iso(System.currentTimeMillis())}: " + if (esito.isEmpty()) "OK ($inviati inviati)" else "$esito ($inviati inviati prima)"
    }

    fun flush(reason: String) = save(build(reason), reason)

    /** Rapporto di una singola elaborazione, con parametri, tempi e miniature prima/dopo */
    fun foto(dettagli: JSONObject, errore: Throwable? = null) {
        val motivo = if (errore == null) "elaborazione" else "elaborazione-fallita"
        save(build(motivo, errore, dettagli = dettagli), motivo)
    }

    fun saveCrash(e: Throwable) {
        log("CRASH: ${e.javaClass.simpleName}: ${e.message}")
        save(build("crash", e), "crash")
    }

    fun exitReasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "app bloccata (non rispondeva)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "memoria del telefono esaurita"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "troppe risorse usate"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "chiusa dall'utente (arresto forzato o aggiornamento)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "fermata dall'utente"
        ApplicationExitInfo.REASON_SIGNALED -> "terminata dal sistema"
        ApplicationExitInfo.REASON_EXIT_SELF -> "uscita normale"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permessi cambiati"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "un servizio da cui dipendeva si è fermato"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "avvio non riuscito"
        13 -> "chiusa dal sistema (altro motivo)"
        14 -> "congelata dal sistema"
        15 -> "stato dell'app cambiato"
        16 -> "aggiornamento dell'app"
        else -> "altro motivo ($reason)"
    }

    /** All'avvio: se la sessione precedente non ha salvato il suo rapporto, lo salva ora */
    private fun recoverPrevious(ctx: Context) {
        val lines = runCatching { journal?.readLines() }.getOrNull().orEmpty().filter { it.isNotBlank() }
        val prefs = ctx.getSharedPreferences("fototool", Context.MODE_PRIVATE)
        val exit = runCatching {
            ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
        }.getOrNull()?.takeIf { it.timestamp > prefs.getLong("ultima_uscita", 0) }
        exit?.let { prefs.edit().putLong("ultima_uscita", it.timestamp).apply() }
        val serious = exit != null && exit.reason in setOf(
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_LOW_MEMORY,
        )
        if (lines.isEmpty() && !serious) return
        synchronized(events) {
            lines.forEach { l ->
                val tab = l.indexOf('\t')
                val t = l.substring(0, tab.coerceAtLeast(0)).toLongOrNull() ?: return@forEach
                events += t to l.substring(tab + 1)
            }
        }
        val info = if (exit != null) JSONObject()
            .put("motivo", exitReasonText(exit.reason))
            .put("descrizione", exit.description ?: "")
            .put("memoriaPss", "${exit.pss shr 10} MB")
            .put("ora", iso(exit.timestamp))
        else JSONObject().put("motivo", "sconosciuto")
        save(build("chiusura-improvvisa", exit = info, withState = false), "chiusura-improvvisa")
    }
}
