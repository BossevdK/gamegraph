package nl.gamegraph.app.ui

import nl.gamegraph.app.domain.Game
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Toont de prijs eerlijk: onbekend is niet hetzelfde als gratis. */
fun priceText(game: Game): String = when (val p = game.priceCents) {
    null -> "Prijs onbekend"
    0 -> "Gratis"
    else -> "€ %d,%02d".format(Locale.forLanguageTag("nl-NL"), p / 100, p % 100)
}

fun formatDateTime(millis: Long): String =
    if (millis <= 0) "nog nooit"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("nl-NL")).format(Date(millis))

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "onbekende grootte"
    bytes < 1024 * 1024 -> "${bytes / 1024} kB"
    else -> "%.1f MB".format(Locale.forLanguageTag("nl-NL"), bytes / 1024.0 / 1024.0)
}
