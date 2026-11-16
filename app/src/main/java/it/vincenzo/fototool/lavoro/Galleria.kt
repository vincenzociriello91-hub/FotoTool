package it.vincenzo.fototool.lavoro

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Una foto della galleria (MediaStore). */
data class Foto(
    val id: Long, val nome: String, val data: Long, val dimensione: Long,
    val larghezza: Int, val altezza: Int, val generazione: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    /** Nome senza estensioni: PXL_20261116_081234567 */
    val base: String get() = nome.substringBefore('.')
}

/** Ricerca dei DNG di Pixel Camera e dei loro JPEG gemelli. */
object Galleria {
    const val PIXEL = "com.google.android.GoogleCamera"
    private val COLONNE = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        MediaStore.MediaColumns.GENERATION_MODIFIED,
    )
    private const val DNG = "${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=? AND " +
        "(${MediaStore.Images.Media.MIME_TYPE}=? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"

    private fun cerca(ctx: Context, dove: String, argomenti: Array<String>, ordine: String, limite: Int = 500): List<Foto> {
        val out = mutableListOf<Foto>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, COLONNE, dove, argomenti, ordine)?.use { c ->
            while (c.moveToNext() && out.size < limite) {
                out += Foto(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getLong(6))
            }
        }
        return out
    }

    /** DNG di Pixel Camera pubblicati o modificati dopo la generazione [dopo] (contatore di MediaStore). */
    fun dngNuove(ctx: Context, dopo: Long): List<Foto> = cerca(
        ctx, "$DNG AND ${MediaStore.MediaColumns.GENERATION_MODIFIED}>?",
        arrayOf(PIXEL, "image/x-adobe-dng", "%.dng", dopo.toString()),
        "${MediaStore.MediaColumns.GENERATION_MODIFIED} ASC",
    )

    fun ultimeDng(ctx: Context, n: Int): List<Foto> =
        cerca(ctx, DNG, arrayOf(PIXEL, "image/x-adobe-dng", "%.dng"), "${MediaStore.Images.Media.DATE_ADDED} DESC", n)

    fun perId(ctx: Context, id: Long): Foto? =
        cerca(ctx, "${MediaStore.Images.Media._ID}=?", arrayOf(id.toString()), "${MediaStore.Images.Media._ID} ASC", 1).firstOrNull()

    /** Il JPEG che Pixel Camera salva insieme al DNG (es. .RAW-01.jpg): il più grande con lo stesso nome base. */
    fun gemello(ctx: Context, foto: Foto): Foto? = cerca(
        // solo file di Pixel Camera: la nostra foto (PXL_…_FotoTool.jpg) ha lo stesso nome base
        ctx, "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE}=? AND " +
            "${MediaStore.Images.Media._ID}<>? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=?",
        arrayOf("${foto.base}%", "image/jpeg", foto.id.toString(), PIXEL), "${MediaStore.Images.Media._ID} ASC",
    ).maxByOrNull { it.larghezza.toLong() * it.altezza }

    fun generazioneAttuale(ctx: Context): Long = MediaStore.getGeneration(ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY)
}
