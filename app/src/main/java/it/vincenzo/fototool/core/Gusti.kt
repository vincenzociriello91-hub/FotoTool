package it.vincenzo.fototool.core

import android.content.Context
import it.vincenzo.fototool.motore.Stile
import it.vincenzo.fototool.motore.Stili
import org.json.JSONObject

/**
 * I gusti di Vincenzo: per ogni tipo di scena (giorno, notte, controluce) conta gli stili che sceglie
 * e alla foto successiva usa quello scelto più spesso (a parità, il più recente).
 */
object Gusti {
    private fun p(ctx: Context) = ctx.getSharedPreferences("gusti", Context.MODE_PRIVATE)

    private fun conti(ctx: Context, scena: String): JSONObject =
        runCatching { JSONObject(p(ctx).getString(scena, "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun stilePer(ctx: Context, scena: String): Stile {
        val c = conti(ctx, scena)
        val ultimo = p(ctx).getString("ultimo_$scena", null)
        val migliore = c.keys().asSequence().maxWithOrNull(compareBy<String>({ c.optInt(it) }, { if (it == ultimo) 1 else 0 }))
        return Stili.perNome(migliore)
    }

    fun scelto(ctx: Context, scena: String, stile: Stile) {
        val c = conti(ctx, scena)
        c.put(stile.nome, c.optInt(stile.nome) + 1)
        p(ctx).edit().putString(scena, c.toString()).putString("ultimo_$scena", stile.nome).apply()
        Reports.log("Gusto imparato: per le foto di $scena preferisce ${stile.nome} (${c.optInt(stile.nome)} scelte)")
    }

    /** Riepilogo leggibile, per la scheda Info e per i rapporti */
    fun riepilogo(ctx: Context): Map<String, String> =
        listOf("giorno", "notte", "controluce").associateWith { s ->
            val c = conti(ctx, s)
            if (c.length() == 0) "Naturale (nessuna scelta ancora)" else "${stilePer(ctx, s).nome} (scelte: $c)"
        }
}
