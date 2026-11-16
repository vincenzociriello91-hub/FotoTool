package it.vincenzo.fototool.lavoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import it.vincenzo.fototool.MainActivity
import it.vincenzo.fototool.core.Reports

/**
 * Servizio sempre attivo con notifica fissa: Android lo tratta come un'app in primo piano, quindi ogni nuovo RAW
 * viene visto subito e sviluppato a piena velocità anche ad app chiusa. Con il solo lavoro programmato
 * le foto aspettavano fino a 47 minuti e lo sviluppo durava fino a 330 s (rapporti del 16/11/2026).
 */
class Servizio : Service() {
    private val filo = HandlerThread("osservatore").apply { start() }
    private val gestore = Handler(filo.looper)
    private val osservatore = object : ContentObserver(gestore) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Pixel Camera scrive più file in fila: si aspetta un attimo e si controlla una volta sola
            gestore.removeCallbacks(controllo)
            gestore.postDelayed(controllo, 1_500)
        }
    }
    private val controllo = Runnable { runCatching { Osservatore.controlla(this) } }

    override fun onCreate() {
        super.onCreate()
        Reports.init(this)
        attivo = true
        startForeground(NOTIFICA, notifica(this, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, osservatore)
        gestore.post(controllo)
        Reports.log("Servizio attivo: osservo le nuove foto RAW")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        attivo = false
        contentResolver.unregisterContentObserver(osservatore)
        filo.quitSafely()
        Reports.log("Servizio fermato")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CANALE = "servizio"
        private const val NOTIFICA = 1
        @Volatile var attivo = false
            private set

        fun avvia(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, Servizio::class.java)) }
                .onFailure { Reports.log("Servizio non avviato: ${it.javaClass.simpleName}: ${it.message}") }
        }

        /** Notifica fissa e discreta; durante il lavoro dice quale foto si sta migliorando. */
        fun notifica(ctx: Context, foto: String?): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANALE) == null) nm.createNotificationChannel(
                NotificationChannel(CANALE, "FotoTool attivo", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mostra che FotoTool è pronto a migliorare le nuove foto RAW"
                    setShowBadge(false)
                }
            )
            val apri = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CANALE)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(if (foto == null) "FotoTool pronto" else "Miglioro la foto…")
                .setContentText(foto ?: "Migliora da solo ogni nuova foto RAW")
                .setContentIntent(apri)
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun aggiorna(ctx: Context, foto: String?) {
            if (!attivo) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICA, notifica(ctx, foto)) }
        }
    }
}
