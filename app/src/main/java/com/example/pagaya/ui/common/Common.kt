package com.example.pagaya.ui.common

import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.pagaya.R
import com.example.pagaya.data.local.ClientWithAccount

/** Pesos colombianos enteros con agrupación por puntos: 45000 → "$45.000". */
fun formatCop(value: Long): String {
    val digits = kotlin.math.abs(value).toString().reversed().chunked(3).joinToString(".").reversed()
    return (if (value < 0) "-$" else "$") + digits
}

/** Texto del estado de cuenta, derivado de los movimientos vigentes (HU-06). */
@Composable
fun accountStatusText(account: ClientWithAccount): String = when {
    account.activeMovementCount == 0 -> stringResource(R.string.account_no_movements)
    account.balanceCop > 0 -> stringResource(R.string.account_owes, formatCop(account.balanceCop))
    account.balanceCop == 0L -> stringResource(R.string.account_paid)
    // Un saldo negativo no es posible con las reglas de V1; se señala en lugar de mostrarlo.
    else -> stringResource(R.string.account_inconsistent)
}

/** Encabezado de pantalla secundaria con acción «Volver» accesible; respeta la barra de estado (edge-to-edge). */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
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
            modifier = Modifier.semantics { heading() },
        )
    }
}
