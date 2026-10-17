package com.example.pagaya.ui.clients

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.pagaya.R
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.domain.FieldChange
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.ui.common.formatCop
import com.example.pagaya.ui.common.formatDayShort

/** Nombre del tipo de movimiento: «Fiado», «Abono» o «Pago completo». */
@Composable
internal fun movementTypeLabel(type: MovementType, origin: MovementOrigin): String = stringResource(
    when {
        type == MovementType.FIADO -> R.string.type_fiado
        origin == MovementOrigin.PAGO_TOTAL -> R.string.type_payment
        else -> R.string.type_abono
    },
)

/** El mismo nombre dentro de una frase: «fiado», «abono» o «pago completo». */
@Composable
internal fun movementTypeInSentence(type: MovementType, origin: MovementOrigin): String = stringResource(
    when {
        type == MovementType.FIADO -> R.string.type_fiado_lower
        origin == MovementOrigin.PAGO_TOTAL -> R.string.type_payment_lower
        else -> R.string.type_abono_lower
    },
)

/** Un cambio en lenguaje cotidiano: «Valor: $20.000 → $25.000». Nunca JSON ni códigos. */
@Composable
internal fun fieldChangeText(change: FieldChange): String = when (change) {
    is FieldChange.Amount -> stringResource(R.string.change_amount, formatCop(change.before), formatCop(change.after))
    is FieldChange.Date -> stringResource(R.string.change_date, formatDayShort(change.before), formatDayShort(change.after))
    is FieldChange.Description -> stringResource(
        R.string.change_description,
        quotedNote(change.before),
        quotedNote(change.after),
    )
    is FieldChange.Origin -> stringResource(
        R.string.change_origin,
        originLabel(change.before),
        originLabel(change.after),
    )
    is FieldChange.Status -> stringResource(R.string.change_status, statusLabel(change.before), statusLabel(change.after))
}

@Composable
internal fun quotedNote(text: String?): String = text?.let { "«$it»" } ?: stringResource(R.string.change_no_description)

@Composable
private fun originLabel(origin: MovementOrigin) =
    stringResource(if (origin == MovementOrigin.PAGO_TOTAL) R.string.type_payment else R.string.type_abono)

@Composable
internal fun statusLabel(active: Boolean) = stringResource(if (active) R.string.status_active else R.string.status_annulled)

/** Motivo por el que corregir un movimiento dejaría el historial inválido, con el abono afectado. */
@Composable
internal fun editRejectionText(rejection: HistoryRejection): String = when (rejection) {
    HistoryRejection.NoDebt -> stringResource(R.string.edit_error_no_debt)
    is HistoryRejection.ExceedsBalance -> stringResource(R.string.edit_error_exceeds, formatCop(rejection.balanceCop))
    is HistoryRejection.NegativeAtDate -> stringResource(R.string.movement_error_date_insufficient)
    is HistoryRejection.UncoveredAbono ->
        stringResource(R.string.edit_error_uncovered, formatCop(rejection.abono.amountCop), formatDayShort(rejection.abono.date))
    HistoryRejection.Overflow -> stringResource(R.string.movement_error_overflow)
}

/** Motivo por el que anular un movimiento dejaría el historial inválido. */
@Composable
internal fun annulRejectionText(rejection: HistoryRejection): String = when (rejection) {
    is HistoryRejection.UncoveredAbono ->
        stringResource(R.string.annul_error_uncovered, formatCop(rejection.abono.amountCop), formatDayShort(rejection.abono.date))
    HistoryRejection.Overflow -> stringResource(R.string.movement_error_overflow)
    else -> stringResource(R.string.annul_error_unavailable)
}
