package it.vincenzo.fototool.lavoro

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import it.vincenzo.fototool.core.Reports

/**
 * Si sveglia quando cambia la galleria: cerca i nuovi DNG di Pixel Camera, li mette in coda e avvia l'elaborazione.
 * I lavori legati ai contenuti valgono una volta sola: a ogni giro si riprogramma.
 */
class Osservatore : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Reports.init(this)
            runCatching { controlla(this) }.onFailure { Reports.log("Osservatore: errore ${it.javaClass.simpleName}: ${it.message}") }
            programma(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val ID = 1001

        /** Mette in coda i DNG nuovi e avvia l'elaborazione. Restituisce quanti ne ha trovati. */
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
            Reports.log("Nuove foto RAW: ${nuove.joinToString { it.nome }}")
            Coda.aggiungi(ctx, nuove.map { it.id })
            Elaborazione.avvia(ctx)
            return nuove.size
        }

        fun programma(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(ID, ComponentName(ctx, Osservatore::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1_500)
                .setTriggerContentMaxDelay(10_000)
                .build()
            runCatching { js.schedule(info) }.onFailure { Reports.log("Osservatore non programmato: ${it.message}") }
        }

        fun programmato(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).getPendingJob(ID) != null
    }
}

/** Dopo il riavvio del telefono o un aggiornamento dell'app, riarma l'osservatore. */
class Avvio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reports.init(ctx)
        Reports.log("Riarmo dopo ${intent.action}")
        Osservatore.programma(ctx)
        Servizio.avvia(ctx)
        if (Coda.attese(ctx).isNotEmpty()) Elaborazione.avvia(ctx)
    }
}
