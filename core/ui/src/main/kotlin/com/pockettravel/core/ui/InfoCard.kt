package com.pockettravel.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Regole comuni dei riquadri (Card) con titolo, uguali in tutte le schermate:
// - contenuto del riquadro con padding Spacing.l (CardPadding), riquadri di un elenco distanziati di Spacing.l (CardGap);
// - titolo del riquadro titleMedium in grassetto, con icona da 24 dp in un cerchio colorato da 40 dp (InfoCardHeader):
//   e' quello che fa distinguere a colpo d'occhio un riquadro dall'altro;
// - descrizione bodyMedium (CardDescription), in onSurfaceVariant nei riquadri neutri;
// - etichette brevi (categoria, rotta, data) labelMedium; suggerimenti e didascalie che vanno a capo bodySmall; titoli di sezione fuori dai riquadri titleLarge, sottotitoli titleSmall;
// - righe a forma di ListItem: titolo bodyLarge, testo di supporto bodyMedium.
object InfoCardDefaults {
    val CardPadding = Spacing.l
    val CardGap = Spacing.l
    val HeaderIconSize = 24.dp
    val HeaderIconCircle = 40.dp
}

// Intestazione di un riquadro: icona, titolo (esposto a TalkBack come titolo) e, a destra, un elemento facoltativo
// (conteggio, azione). L'etichetta sopra il titolo (overline) e' per i riquadri che la ripetono da una categoria.
@Composable
fun InfoCardHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalContentColor.current,
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(InfoCardDefaults.HeaderIconCircle).background(iconTint.copy(alpha = 0.14f), CircleShape),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(InfoCardDefaults.HeaderIconSize))
        }
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            if (overline != null) {
                Text(text = overline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(Spacing.s))
            trailing()
        }
    }
}

// Testo descrittivo di un riquadro. Colore non specificato = quello del riquadro (riquadri colorati);
// nei riquadri neutri passare MaterialTheme.colorScheme.onSurfaceVariant.
@Composable
fun CardDescription(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}
