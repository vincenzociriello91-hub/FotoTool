package it.vincenzo.fototool.lavoro

import android.app.job.JobScheduler
import android.content.Context
import it.vincenzo.fototool.core.Reports

/**
 * Cerca i nuovi DNG di Pixel Camera e li mette in coda, senza svilupparli:
 * dalla 1.0.11 lo sviluppo parte solo quando Vincenzo preme il pulsante.
 */
object Osservatore {
    private const val ID = 1001

    /** Mette in coda i DNG nuovi. Restituisce quanti ne ha trovati. */
    fun controlla(ctx: Context): Int {
        val gen = Coda.generazione(ctx)
        if (gen < 0) {
            // primo avvio: si parte da adesso, le foto vecchie si elaborano solo a richiesta
            Coda.impostaGenerazione(ctx, Galleria.generazioneAttuale(ctx))
            return 0
        }
        val trovate = Galleria.dngNuove(ctx, gen)
        val nuove = trovate.filter { !Archivio.contiene(ctx, it.id) }
        val massima = trovate.maxOfOrNull { it.generazione }
        if (massima != null) Coda.impostaGenerazione(ctx, massima)
        if (nuove.isEmpty()) return 0
        Reports.log("Nuove foto RAW in attesa del pulsante: ${nuove.joinToString { it.nome }}")
        Coda.aggiungi(ctx, nuove.map { it.id })
        return nuove.size
    }

    /** Toglie il lavoro automatico lasciato dalle versioni fino alla 1.0.10. */
    fun annullaAutomatico(ctx: Context) = runCatching { ctx.getSystemService(JobScheduler::class.java).cancel(ID) }
}
