package it.vincenzo.fototool.lavoro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Elenco delle foto già migliorate, salvato nei file dell'app. */
object Archivio {
    data class Voce(
        val id: Long,
        val nome: String,
        val uscita: String,
        val originale: String?,
        val stile: String,
        val scena: String,
        val quando: Long,
        val ms: Long,
        val dimensione: String,
        val ultraHdr: Boolean,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("nome", nome).put("uscita", uscita).put("originale", originale ?: "")
            .put("stile", stile).put("scena", scena).put("quando", quando).put("ms", ms).put("dimensione", dimensione).put("ultraHdr", ultraHdr)

        companion object {
            fun da(o: JSONObject) = Voce(
                o.getLong("id"), o.optString("nome"), o.optString("uscita"), o.optString("originale").ifBlank { null },
                o.optString("stile", "Naturale"), o.optString("scena", "giorno"), o.optLong("quando"), o.optLong("ms"),
                o.optString("dimensione"), o.optBoolean("ultraHdr"),
            )
        }
    }

    private val stato = MutableStateFlow<List<Voce>?>(null)
    val flusso: StateFlow<List<Voce>?> = stato

    private fun file(ctx: Context) = File(ctx.filesDir, "archivio.json")

    @Synchronized fun voci(ctx: Context): List<Voce> {
        stato.value?.let { return it }
        val l = runCatching {
            val a = JSONArray(file(ctx).readText())
            (0 until a.length()).map { Voce.da(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        stato.value = l
        return l
    }

    @Synchronized fun salva(ctx: Context, v: Voce) {
        val l = listOf(v) + voci(ctx).filter { it.id != v.id }
        val a = JSONArray(); l.forEach { a.put(it.json()) }
        val tmp = File(ctx.filesDir, "archivio.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
        stato.value = l
    }

    fun contiene(ctx: Context, id: Long) = voci(ctx).any { it.id == id }

    /** Toglie le voci indicate (le foto salvate restano): servono per rifarle con un motore migliore. */
    @Synchronized fun togli(ctx: Context, ids: Collection<Long>) {
        val l = voci(ctx).filter { it.id !in ids }
        val a = JSONArray(); l.forEach { a.put(it.json()) }
        file(ctx).writeText(a.toString())
        stato.value = l
    }

    fun miniatura(ctx: Context, id: Long, tipo: String) = File(File(ctx.filesDir, "miniature").apply { mkdirs() }, "${id}_$tipo.jpg")
}
