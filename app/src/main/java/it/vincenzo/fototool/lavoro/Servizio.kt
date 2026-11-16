package it.vincenzo.fototool.lavoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import it.vincenzo.fototool.core.Reports

/**
 * Attivo solo mentre si migliorano le foto chieste col pulsante: così Android non rallenta il lavoro
 * se esci dall'app (con un semplice thread si passava da 13 s a 60-190 s per foto, rapporti del 16/11/2026).
 */
class Servizio : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Reports.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null)
            nm.createNotificationChannel(NotificationChannel(CANALE, "Miglioramento foto", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CANALE).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("FotoTool").setContentText("Miglioro le foto RAW…").setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // a schermo spento il telefono si addormentava a metà sviluppo: 100-185 s invece di 15 (rapporti del 16/11/2026)
        val sveglia = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FotoTool:miglioramento")
        sveglia.acquire(60 * 60 * 1000L)
        Thread {
            try { Elaborazione.svuota(applicationContext) { false } } finally { if (sveglia.isHeld) sveglia.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object { const val CANALE = "miglioramento" }
}
