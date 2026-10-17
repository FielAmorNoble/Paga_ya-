package com.example.pagaya.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pagaya.R
import com.example.pagaya.data.local.ClientWithAccount
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Pesos colombianos enteros con agrupación por puntos: 45000 → "$45.000". */
fun formatCop(value: Long): String {
    val digits = kotlin.math.abs(value).toString().reversed().chunked(3).joinToString(".").reversed()
    return (if (value < 0) "-$" else "$") + digits
}

/** Importe con signo explícito para el historial: fiado «+$60.000», abono «−$15.000» (signo menos U+2212). */
fun formatSignedCop(amountCop: Long, positive: Boolean): String = (if (positive) "+" else "−") + formatCop(amountCop)

/** Fecha numérica de los formularios: 05/10/2026. */
fun formatDayNumeric(date: LocalDate): String = NUMERIC_DAY.format(date)

/** Fecha completa para lectores de pantalla: «5 de octubre de 2026». */
fun formatDayLong(date: LocalDate): String = LONG_DAY.format(date)

/** Fecha y hora del cambio en Bogotá: «12 oct 2026, 4:35 p. m.». */
@Composable
fun formatDateTime(millis: Long): String {
    val moment = java.time.Instant.ofEpochMilli(millis).atZone(com.example.pagaya.domain.BusinessCalendar.ZONE)
    return formatDayShort(moment.toLocalDate()) + ", " + TIME_OF_DAY.format(moment)
}

/** Fecha breve del historial: «5 oct 2026». */
@Composable
fun formatDayShort(date: LocalDate): String =
    "${date.dayOfMonth} ${stringArrayResource(R.array.months_short)[date.monthValue - 1]} ${date.year}"

private val SPANISH_COLOMBIA: Locale = Locale.forLanguageTag("es-CO")
private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", SPANISH_COLOMBIA)
private val NUMERIC_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(SPANISH_COLOMBIA)

/** Texto del estado de cuenta, derivado de los movimientos vigentes (HU-06). */
@Composable
fun accountStatusText(account: ClientWithAccount): String = when {
    account.activeMovementCount == 0 && account.totalMovementCount > 0 -> stringResource(R.string.account_no_active_movements)
    account.activeMovementCount == 0 -> stringResource(R.string.account_no_movements)
    account.balanceCop > 0 -> stringResource(R.string.account_owes, formatCop(account.balanceCop))
    account.balanceCop == 0L -> stringResource(R.string.account_paid)
    // Un saldo negativo no es posible con las reglas de V1; se señala en lugar de mostrarlo.
    else -> stringResource(R.string.account_inconsistent)
}

/**
 * Encabezado de pantalla secundaria con acción «Volver» accesible; respeta la barra de estado
 * (edge-to-edge). [actions] ocupa el extremo derecho.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}
