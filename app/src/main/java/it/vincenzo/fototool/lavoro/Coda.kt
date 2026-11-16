package it.vincenzo.fototool.lavoro

import android.content.Context

/** Foto RAW in attesa di essere elaborate e ultimo punto della galleria già controllato. */
object Coda {
    private fun p(ctx: Context) = ctx.getSharedPreferences("coda", Context.MODE_PRIVATE)

    @Synchronized fun attese(ctx: Context): List<Long> =
        p(ctx).getString("ids", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    @Synchronized fun aggiungi(ctx: Context, ids: List<Long>) {
        val tutte = (attese(ctx) + ids).distinct()
        p(ctx).edit().putString("ids", tutte.joinToString(",")).apply()
    }

    @Synchronized fun togli(ctx: Context, id: Long) {
        p(ctx).edit().putString("ids", attese(ctx).filter { it != id }.joinToString(",")).apply()
    }

    @Synchronized fun prossima(ctx: Context): Long? = attese(ctx).firstOrNull()

    /** Prende e toglie subito la prossima foto: chi la prende è l'unico a elaborarla. */
    @Synchronized fun prendi(ctx: Context): Long? = attese(ctx).firstOrNull()?.also { togli(ctx, it) }

    /** Generazione di MediaStore già controllata; -1 = mai (al primo avvio si parte da adesso, non dal passato). */
    fun generazione(ctx: Context): Long = p(ctx).getLong("generazione", -1)
    fun impostaGenerazione(ctx: Context, g: Long) = p(ctx).edit().putLong("generazione", g).apply()
}
