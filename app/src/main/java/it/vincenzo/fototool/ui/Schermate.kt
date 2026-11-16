package it.vincenzo.fototool.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.vincenzo.fototool.BuildConfig
import it.vincenzo.fototool.core.Gusti
import it.vincenzo.fototool.core.Reports
import it.vincenzo.fototool.core.Updater
import it.vincenzo.fototool.lavoro.Archivio
import it.vincenzo.fototool.lavoro.Coda
import it.vincenzo.fototool.lavoro.Elaboratore
import it.vincenzo.fototool.lavoro.Elaborazione
import it.vincenzo.fototool.lavoro.Galleria
import it.vincenzo.fototool.lavoro.Osservatore
import it.vincenzo.fototool.lavoro.Servizio
import it.vincenzo.fototool.motore.Stili
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun haFoto(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
private fun parziale(ctx: Context) = !haFoto(ctx) &&
    ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

@Composable
fun App() {
    var scheda by remember { mutableIntStateOf(0) }
    var aperta by remember { mutableStateOf<Archivio.Voce?>(null) }
    val voce = aperta
    if (voce != null) {
        Dettaglio(voce) { aperta = null }
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(scheda == 0, { scheda = 0 }, { Icon(Icons.Filled.PhotoLibrary, null) }, label = { Text("Foto") })
                NavigationBarItem(scheda == 1, { scheda = 1 }, { Icon(Icons.Filled.Info, null) }, label = { Text("Info") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (scheda == 0) SchermataFoto { aperta = it } else SchermataInfo()
        }
    }
}

@Composable
fun SchermataFoto(apri: (Archivio.Voce) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var permesso by remember { mutableStateOf(haFoto(ctx)) }
    val voci by Archivio.flusso.collectAsState()
    val inCorso by Elaboratore.inCorso.collectAsState()
    val chiedi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permesso = haFoto(ctx)
        Reports.log("Permessi foto: ${it.entries.joinToString { (k, v) -> "${k.substringAfterLast('.')}=$v" }}")
        if (permesso) scope.launch(Dispatchers.IO) { Osservatore.programma(ctx); Servizio.avvia(ctx); Osservatore.controlla(ctx) }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Archivio.voci(ctx) } }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { permesso = haFoto(ctx); onPauseOrDispose { } }

    LazyVerticalGrid(
        GridCells.Adaptive(160.dp), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("FotoTool", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Le tue foto RAW di Pixel Camera, migliorate da sole.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!permesso) Sezione("Serve l'accesso alle foto", Icons.Filled.PhotoLibrary, Rgb.Rosso,
                    "Per vedere i RAW di Pixel Camera e salvare le versioni migliorate") {
                    Text("Scegli \"Consenti tutte\": con l'accesso solo ad alcune foto FotoTool non vede quelle nuove.", style = MaterialTheme.typography.bodySmall)
                    if (parziale(ctx)) {
                        Text("Ora FotoTool vede solo alcune foto. Nelle impostazioni: Autorizzazioni → Foto e video → \"Consenti sempre tutte\".",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Apri le impostazioni dell'app") }
                    } else Button(onClick = {
                        chiedi.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                            Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                    }) { Text("Consenti accesso alle foto") }
                }
                Sezione("Automatico", Icons.Filled.AutoAwesome, Rgb.Verde,
                    if (inCorso != null) "Sto migliorando ${inCorso}…" else "In attesa di nuove foto RAW") {
                    if (inCorso != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Scatta con Pixel Camera con RAW attivo (Impostazioni → Avanzate → RAW + JPEG). " +
                        "Non devi premere niente: FotoTool trova il DNG da solo (anche ad app chiusa), lo sviluppa e salva la versione migliorata in DCIM/FotoTool in Ultra HDR. L'originale resta com'è.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!voci.isNullOrEmpty()) Text("Migliorate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        items(voci.orEmpty(), key = { it.id }) { v -> Scheda(v) { apri(v) } }
    }
}

@Composable
private fun Scheda(v: Archivio.Voce, apri: () -> Unit) {
    val ctx = LocalContext.current
    val mini = remember(v.id, v.quando) {
        runCatching { BitmapFactory.decodeFile(Archivio.miniatura(ctx, v.id, "dopo").path)?.asImageBitmap() }.getOrNull()
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.clickable(onClick = apri)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(GrigioFoto), contentAlignment = Alignment.Center) {
                if (mini != null) Image(mini, v.nome, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pallino(coloreStile(v.stile))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(v.stile, style = MaterialTheme.typography.labelLarge)
                    Text(SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(v.quando)) + " · " + v.scena,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Foto a circa [lato] pixel, con la sua gain map Ultra HDR se c'è. */
private fun carica(ctx: Context, uri: Uri, lato: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= lato) s *= 2
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
}.getOrNull()

@Composable
fun Dettaglio(voceIniziale: Archivio.Voce, chiudi: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var voce by remember { mutableStateOf(voceIniziale) }
    var nostra by remember { mutableStateOf<Bitmap?>(null) }
    var google by remember { mutableStateOf<Bitmap?>(null) }
    var premuto by remember { mutableStateOf(false) }
    var lavoro by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = chiudi)

    // Ultra HDR: la finestra passa in modalità HDR solo mentre si guarda una foto
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose { w?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
    }
    LaunchedEffect(voce.uscita, voce.quando) {
        nostra = withContext(Dispatchers.IO) { carica(ctx, Uri.parse(voce.uscita), 2400) }
        if (google == null) google = withContext(Dispatchers.IO) { voce.originale?.let { carica(ctx, Uri.parse(it), 2400) } }
    }

    Box(Modifier.fillMaxSize().background(GrigioFoto)) {
        val img = if (premuto && google != null) google else nostra
        if (img != null) Image(
            img.asImageBitmap(), voce.nome,
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onPress = { premuto = true; tryAwaitRelease(); premuto = false })
            },
            contentScale = ContentScale.Fit,
        )
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = chiudi) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(if (premuto) "Originale di Google" else "FotoTool · ${voce.stile}", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Tieni premuto sulla foto per vedere l'originale", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lavoro?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stili.TUTTI.forEach { s ->
                    FilterChip(
                        selected = voce.stile == s.nome, enabled = lavoro == null,
                        onClick = {
                            if (voce.stile == s.nome) return@FilterChip
                            lavoro = "Rifaccio la foto in stile ${s.nome}…"
                            scope.launch {
                                val esito = withContext(Dispatchers.Default) {
                                    runCatching {
                                        val foto = Galleria.perId(ctx, voce.id) ?: error("il DNG originale non c'è più")
                                        Gusti.scelto(ctx, voce.scena, s)
                                        Elaboratore.elabora(ctx, foto, s)
                                    }
                                }
                                lavoro = null
                                esito.onSuccess { voce = it }.onFailure {
                                    Reports.log("Cambio stile non riuscito: ${it.message}")
                                    android.widget.Toast.makeText(ctx, "Non riuscito: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                                launch(Dispatchers.IO) { Reports.sendQueue() }
                            }
                        },
                        label = { Text(s.nome) },
                        leadingIcon = { Pallino(coloreStile(s.nome)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Black.copy(alpha = 0.3f), labelColor = Color.White,
                            selectedContainerColor = coloreStile(s.nome).copy(alpha = 0.35f), selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
            Text("Scena: ${voce.scena} · ${voce.dimensione} · ${voce.ms / 1000.0} s · Ultra HDR: ${if (voce.ultraHdr) "sì" else "no"}",
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(voce.uscita), "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Apri in Foto", color = Color.White) }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(voce.uscita)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Condividi"))
                }) { Icon(Icons.Filled.Share, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Condividi", color = Color.White) }
            }
        }
    }
}

@Composable
fun SchermataInfo() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var msgAgg by remember { mutableStateOf<String?>(null) }
    var invio by remember { mutableStateOf(Reports.lastSend) }
    var novita by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Sezione("FotoTool", Icons.Filled.NewReleases, Rgb.Blu, "Versione ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {
            msgAgg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    msgAgg = "Controllo…"
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { Updater.latest() }
                        release = r.getOrNull()?.takeIf { Updater.isNewer(it) }
                        msgAgg = when {
                            r.isFailure -> "Controllo non riuscito: ${r.exceptionOrNull()?.message}"
                            release != null -> "Disponibile ${release!!.name}:\n${release!!.notes}"
                            else -> "Hai già l'ultima versione."
                        }
                    }
                }) { Text("Controlla aggiornamenti") }
                release?.let { rel ->
                    Button(onClick = {
                        msgAgg = "Download in corso…"
                        scope.launch(Dispatchers.IO) { runCatching { Updater.install(ctx, rel) }.onFailure { msgAgg = "Download non riuscito: ${it.message}" } }
                    }) { Text("Installa") }
                }
            }
            OutlinedButton(onClick = { novita = !novita }) { Text(if (novita) "Nascondi novità" else "Novità") }
            if (novita) Text(
                remember { runCatching { ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrDefault("") },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Sezione("I tuoi gusti", Icons.Filled.Psychology, Rgb.Verde, "Lo stile che FotoTool usa da solo per ogni tipo di scena") {
            Gusti.riepilogo(ctx).forEach { (scena, stile) -> Text("${scena.replaceFirstChar { it.uppercase() }}: $stile", style = MaterialTheme.typography.bodyMedium) }
            Text("Si aggiornano quando cambi stile a una foto.", style = MaterialTheme.typography.bodySmall)
        }
        Sezione("Rapporti", Icons.Filled.Description, Rgb.Rosso, "Cifrati, li legge solo Claude per migliorare l'app") {
            Text("Ultimo invio: $invio", style = MaterialTheme.typography.bodySmall)
            Text("Copia leggibile sul telefono: Download/${Reports.FOLDER}", style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = {
                scope.launch(Dispatchers.IO) { Reports.flush("manuale"); Reports.sendQueue(); invio = Reports.lastSend }
            }) { Text("Invia rapporto ora") }
        }
        Spacer(Modifier.height(12.dp))
    }
}
