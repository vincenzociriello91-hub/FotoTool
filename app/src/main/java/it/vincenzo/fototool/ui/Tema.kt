package it.vincenzo.fototool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Stile di DroneTool (scelto da Vincenzo): base DJI con blu cielo, nero e grafite nel tema scuro,
 * bianco e grigi chiari in quello chiaro; rosso, verde e blu morbidi solo nei dettagli. Non usa i colori dello sfondo.
 */
object Rgb {
    val Rosso = Color(0xFFF0605D)
    val Verde = Color(0xFF4CC38A)
    val Blu = Color(0xFF3D8EF0)
}

val DjiBlu = Color(0xFF0070D5)

/** Grigio scuro neutro dietro le foto: non altera la percezione dei colori. */
val GrigioFoto = Color(0xFF262626)

@Composable
fun FotoToolTheme(scuro: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val schema = if (scuro) darkColorScheme(
        primary = Color(0xFF4DA3FF), onPrimary = Color(0xFF001B3A),
        primaryContainer = Color(0xFF0B3C73), onPrimaryContainer = Color(0xFFD6E6FF),
        secondary = Rgb.Verde, tertiary = Rgb.Rosso,
        background = Color(0xFF0E0F11), onBackground = Color(0xFFE6E8EB),
        surface = Color(0xFF16181B), onSurface = Color(0xFFE6E8EB),
        surfaceVariant = Color(0xFF22262B), onSurfaceVariant = Color(0xFFB4BAC2),
        surfaceContainer = Color(0xFF1A1D21), surfaceContainerHigh = Color(0xFF20242A),
        outline = Color(0xFF3A4048),
    ) else lightColorScheme(
        primary = DjiBlu, onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E6FF), onPrimaryContainer = Color(0xFF001B3A),
        secondary = Color(0xFF1E8A5A), tertiary = Color(0xFFB3261E),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF15171A),
        surface = Color(0xFFF6F7F9), onSurface = Color(0xFF15171A),
        surfaceVariant = Color(0xFFE9ECF0), onSurfaceVariant = Color(0xFF4A5059),
        surfaceContainer = Color(0xFFF1F3F6), surfaceContainerHigh = Color(0xFFEBEEF2),
        outline = Color(0xFFC4C9D0),
    )
    MaterialTheme(colorScheme = schema, content = content)
}

/** Colore di riconoscimento di ogni stile, uguale in tutta l'app. */
fun coloreStile(nome: String): Color = when (nome) {
    "Vivido" -> Rgb.Rosso
    "Morbido" -> Rgb.Blu
    "Bianco e nero" -> Color(0xFF9AA0A8)
    else -> Rgb.Verde
}

/** Scheda di sezione: icona su fondo velato del suo colore, titolo, contenuto. */
@Composable
fun Sezione(titolo: String, icona: ImageVector, colore: Color = Rgb.Blu, sottotitolo: String? = null, content: @Composable () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colore.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(icona, null, tint = colore)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(titolo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (sottotitolo != null) Text(sottotitolo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
fun Pallino(colore: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(colore))
