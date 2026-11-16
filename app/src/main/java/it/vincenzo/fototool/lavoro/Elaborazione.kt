package it.vincenzo.fototool.lavoro

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import it.vincenzo.fototool.core.Reports

/** Elabora la coda una foto alla volta, in background, appena possibile. */
class Elaborazione : JobService() {
    @Volatile private var ferma = false

    override fun onStartJob(params: JobParameters): Boolean {
        ferma = false
        Thread {
            Reports.init(this)
            Reports.log("Elaborazione avviata (${Coda.attese(this).size} in coda)")
            while (!ferma) {
                val id = Coda.prossima(this) ?: break
                val foto = Galleria.perId(this, id)
                if (foto == null || Archivio.contiene(this, id)) { Coda.togli(this, id); continue }
                // Pixel Camera salva il JPEG gemello quasi insieme al DNG: se manca, si aspetta un attimo
                if (Galleria.gemello(this, foto) == null && System.currentTimeMillis() - foto.data < 60_000) SystemClock.sleep(5_000)
                runCatching { Elaboratore.elabora(this, foto, null) }
                    .onFailure { Reports.log("Elaborazione di ${foto.nome} non riuscita: ${it.javaClass.simpleName}: ${it.message}") }
                // anche se fallisce si toglie dalla coda: niente tentativi all'infinito (l'errore è nel rapporto)
                Coda.togli(this, id)
            }
            if (ferma) Reports.log("Elaborazione fermata dal sistema, riprende più tardi")
            Reports.sendQueue()
            jobFinished(params, ferma)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        ferma = true
        return true
    }

    companion object {
        private const val ID = 1002

        fun avvia(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            val info = JobInfo.Builder(ID, ComponentName(ctx, Elaborazione::class.java))
                .setExpedited(true)
                .build()
            runCatching { js.schedule(info) }.recoverCatching {
                // senza quota per i lavori urgenti: lavoro normale, il sistema lo esegue appena può
                js.schedule(JobInfo.Builder(ID, ComponentName(ctx, Elaborazione::class.java)).build())
            }.onFailure { Reports.log("Elaborazione non programmata: ${it.message}") }
        }
    }
}
