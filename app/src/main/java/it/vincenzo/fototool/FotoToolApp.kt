package it.vincenzo.fototool

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import it.vincenzo.fototool.core.Reports
import it.vincenzo.fototool.lavoro.Coda
import it.vincenzo.fototool.lavoro.Elaborazione
import it.vincenzo.fototool.lavoro.Osservatore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FotoToolApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
