package com.piyushbodaa.autark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.piyushbodaa.autark.ui.theme.LocalPalette
import com.piyushbodaa.autark.ui.theme.Type

// The handful of pieces piyushbodaa.com is built from, in Compose.

val PillShape = RoundedCornerShape(percent = 50)

@Composable
fun Wordmark(text: String = "Autark", modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.wordmark, color = LocalPalette.current.ink2, modifier = modifier)
}

/** Uppercase, centred, with the second half in grey. */
@Composable
fun Headline(lead: String, rest: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        buildAnnotatedString {
            append(lead.uppercase())
            append(' ')
            withStyle(Type.headline.toSpanStyle().copy(color = p.grey)) { append(rest.uppercase()) }
        },
        style = Type.headline,
        color = p.ink2,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false, enabled: Boolean = true) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(PillShape)
            .background(if (solid) p.solid else p.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text.uppercase(),
            style = Type.label,
            color = when {
                !enabled -> p.grey
                solid -> p.onSolid
                else -> p.ink
            },
            maxLines = 1,
        )
    }
}

/** The site's dock links: small, uppercase, muted until pressed. */
@Composable
fun NavText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(text.uppercase(), style = Type.label, color = if (enabled) p.muted else p.line)
    }
}

@Composable
fun Fine(text: String, modifier: Modifier = Modifier, upper: Boolean = false, align: TextAlign? = null) {
    Text(
        if (upper) text.uppercase() else text,
        style = Type.fine,
        color = LocalPalette.current.muted,
        textAlign = align,
        modifier = modifier,
    )
}
