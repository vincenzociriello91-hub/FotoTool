package it.vincenzo.fototool

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import it.vincenzo.fototool.core.Reports
import it.vincenzo.fototool.lavoro.Archivio
import it.vincenzo.fototool.lavoro.Coda
import it.vincenzo.fototool.lavoro.Elaborazione
import it.vincenzo.fototool.lavoro.Osservatore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FotoToolApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Quando il motore cambia in modo importante, le foto già fatte si rifanno da sole (una volta). */
    private fun rifaiConMotoreNuovo() {
        val p = getSharedPreferences("fototool", MODE_PRIVATE)
        if (p.getInt("motore", 0) >= MOTORE) return
        p.edit().putInt("motore", MOTORE).apply()
        scope.launch {
            val ids = Archivio.voci(this@FotoToolApp).map { it.id }
            if (ids.isEmpty()) return@launch
            Reports.log("Motore aggiornato alla versione $MOTORE: rifaccio ${ids.size} foto")
            Archivio.togli(this@FotoToolApp, ids)
            Coda.aggiungi(this@FotoToolApp, ids)
            Elaborazione.avvia(this@FotoToolApp)
        }
    }

    companion object {
        /** 2 = bianco stimato dal JPEG di Google (1.0.2); 3 = tono e saturazione come Google, zone adattive (1.0.3); 4 = dominante corretta per canale (1.0.4);
         *  5 = nitidezza su due scale, meno levigatura, niente alone viola (1.0.6) */
        const val MOTORE = 5
    }

    override fun onCreate() {
        super.onCreate()
        Reports.init(this)
        Reports.log("Avvio app")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Reports.saveCrash(e) }
            previous?.uncaughtException(t, e)
        }
        Osservatore.programma(this)
        rifaiConMotoreNuovo()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Reports.log("App in primo piano")
                scope.launch {
                    runCatching { Osservatore.controlla(this@FotoToolApp) }
                    if (Coda.attese(this@FotoToolApp).isNotEmpty()) Elaborazione.avvia(this@FotoToolApp)
                    Reports.sendQueue()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                // chiusura normale: rapporto completo dell'utilizzo
                Reports.log("App in background / chiusa")
                scope.launch {
                    Reports.flush("chiusura")
                    Reports.sendQueue()
                }
            }
        })
    }
}
