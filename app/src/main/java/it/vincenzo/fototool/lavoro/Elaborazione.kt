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
            svuota(this) { ferma }
            if (ferma) Reports.log("Elaborazione fermata dal sistema, riprende più tardi")
            jobFinished(params, ferma && Coda.attese(this).isNotEmpty())
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        ferma = true
        return true
    }

    companion object {
        private const val ID = 1002
        private val inLavoro = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Svuota la coda una foto alla volta. Uno solo per volta: prima, lavoro programmato e app potevano
         * rifare la stessa foto due volte (rapporti del 16/11/2026).
         */
        fun svuota(ctx: Context, ferma: () -> Boolean) {
            if (!inLavoro.compareAndSet(false, true)) return
            try {
                Reports.log("Elaborazione avviata (${Coda.attese(ctx).size} in coda)")
                while (!ferma()) {
                    val id = Coda.prendi(ctx) ?: break
                    val foto = Galleria.perId(ctx, id)
                    if (foto == null || Archivio.contiene(ctx, id)) continue
                    // Pixel Camera salva il JPEG gemello quasi insieme al DNG: se manca, si aspetta un attimo
                    if (Galleria.gemello(ctx, foto) == null && System.currentTimeMillis() - foto.data < 60_000) SystemClock.sleep(5_000)
                    // se fallisce non si riprova all'infinito: l'errore è nel rapporto
                    runCatching { Elaboratore.elabora(ctx, foto, null) }
                        .onFailure { Reports.log("Elaborazione di ${foto.nome} non riuscita: ${it.javaClass.simpleName}: ${it.message}") }
                }
                Reports.sendQueue()
            } finally {
                inLavoro.set(false)
            }
        }

        /**
         * Con l'app aperta si elabora subito nell'app, a piena velocità; in background con un lavoro programmato,
         * che Android esegue più lentamente (fino a 100-280 s per foto nei rapporti).
         */
        fun avvia(ctx: Context) {
            val inPrimoPiano = runCatching {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            }.getOrDefault(false)
            if (inPrimoPiano && runCatching { ctx.startForegroundService(android.content.Intent(ctx, Servizio::class.java)) }
                    .onFailure { Reports.log("Servizio non avviato: ${it.javaClass.simpleName}: ${it.message}") }.isSuccess) return
            if (inPrimoPiano) {
                Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                    svuota(ctx.applicationContext) { false }
                }.start()
                return
            }
            avviaLavoro(ctx)
        }

        private fun avviaLavoro(ctx: Context) {
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
