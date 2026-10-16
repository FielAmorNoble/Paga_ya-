package com.example.pagaya.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.local.ClientEntity
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.domain.BusinessCalendar
import com.example.pagaya.domain.HistoryRow
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.formatCop
import com.example.pagaya.ui.common.formatDayLong
import com.example.pagaya.ui.common.formatDayNumeric
import com.example.pagaya.ui.common.formatDayShort
import com.example.pagaya.ui.common.formatSignedCop
import android.content.ClipData
import android.os.Build
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ClientDetailScreen(
    onEdit: (clientId: String) -> Unit,
    onNewMovement: (clientId: String, type: MovementType) -> Unit,
    onBack: () -> Unit,
    /** Tipo del movimiento recién guardado por el formulario ([MovementType.name]); `null` si no hay aviso. */
    savedMovement: String?,
    onSavedMovementShown: () -> Unit,
    viewModel: ClientDetailViewModel = viewModel(factory = ClientDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val payFull by viewModel.payFull.collectAsStateWithLifecycle()
    val paymentSaved by viewModel.paymentSaved.collectAsStateWithLifecycle()
    val loaded = state as? ClientDetailState.Loaded
    val title = loaded?.account?.client?.name.orEmpty()

    // Sobrevive a la rotación. El historial no se recompone al abrir el diálogo, así que no pierde su posición.
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val fiadoSaved = stringResource(R.string.saved_fiado)
    val abonoSaved = stringResource(R.string.saved_abono)
    val paymentMessage = stringResource(R.string.saved_payment)
    LaunchedEffect(savedMovement) {
        val text = when (savedMovement) {
            MovementType.FIADO.name -> fiadoSaved
            MovementType.ABONO.name -> abonoSaved
            else -> null
        }
        if (text != null) {
            onSavedMovementShown()
            snackbarHostState.showSnackbar(text)
        }
    }
    LaunchedEffect(paymentSaved) {
        if (paymentSaved) {
            viewModel.consumePaymentSaved()
            snackbarHostState.showSnackbar(paymentMessage)
        }
    }

    Scaffold(
        topBar = {
            ScreenHeader(title = title, onBack = onBack) {
                if (loaded != null) {
                    val infoDescription = stringResource(R.string.detail_info_description)
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = infoDescription)
                    }
                    val editDescription = stringResource(R.string.detail_edit)
                    TextButton(
                        onClick = { onEdit(loaded.account.client.id) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = editDescription },
                    ) { Text(stringResource(R.string.detail_edit_action)) }
                }
            }
        },
        bottomBar = {
            if (loaded != null && loaded.account.client.active) {
                MovementBar(
                    canAddPayment = loaded.account.balanceCop > 0,
                    onFiado = { onNewMovement(loaded.account.client.id, MovementType.FIADO) },
                    onAbono = { onNewMovement(loaded.account.client.id, MovementType.ABONO) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                ClientDetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ClientDetailState.NotFound -> Text(
                    stringResource(R.string.detail_not_found),
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
                ClientDetailState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.clients_load_error), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                is ClientDetailState.Loaded -> AccountContent(
                    account = current.account,
                    history = current.history,
                    onPayFull = viewModel::startPayFull,
                    // Con la barra inferior, el Scaffold ya reserva la barra de navegación.
                    reserveNavigationBar = !current.account.client.active,
                )
            }
        }
    }

    if (showInfo && loaded != null) {
        ClientInfoDialog(loaded.account.client, onDismiss = { showInfo = false })
    }
    PayFullDialogs(
        state = payFull,
        clientName = title,
        onConfirm = viewModel::confirmPayFull,
        onDismiss = viewModel::dismissPayFull,
    )
}

@Composable
private fun MovementBar(canAddPayment: Boolean, onFiado: () -> Unit, onAbono: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onFiado, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.movement_add_fiado), style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onAbono, enabled = canAddPayment, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.movement_add_abono), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (!canAddPayment) {
                Text(
                    stringResource(R.string.movement_no_balance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountContent(
    account: ClientWithAccount,
    history: List<HistoryRow>,
    onPayFull: () -> Unit,
    reserveNavigationBar: Boolean,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item(key = "balance") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BalanceCard(account)
                if (account.balanceCop > 0) {
                    OutlinedButton(onClick = onPayFull, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.movement_pay_full), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        item(key = "history-title") {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
        }
        if (history.isEmpty()) {
            item(key = "history-empty") {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(history, key = { it.entry.id }) { row ->
            HistoryItem(row)
            HorizontalDivider()
        }
        if (reserveNavigationBar) item(key = "nav-space") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun BalanceCard(account: ClientWithAccount) {
    val description = when {
        account.activeMovementCount == 0 -> stringResource(R.string.account_no_movements)
        account.balanceCop == 0L -> stringResource(R.string.detail_paid_description)
        else -> stringResource(R.string.detail_balance_description, formatCop(account.balanceCop))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).semantics(mergeDescendants = true) { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.detail_balance_label), style = MaterialTheme.typography.labelLarge)
            if (account.activeMovementCount == 0) {
                // Una cuenta sin movimientos vigentes no es una cuenta pagada.
                Text(stringResource(R.string.account_no_movements), style = MaterialTheme.typography.headlineSmall)
            } else {
                Text(
                    formatCop(account.balanceCop),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (account.balanceCop == 0L) {
                    Text(
                        stringResource(R.string.account_paid),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(row: HistoryRow) {
    val entry = row.entry
    val isPayment = entry.type == MovementType.ABONO
    val label = stringResource(
        when {
            !isPayment -> R.string.history_fiado
            entry.origin == MovementOrigin.PAGO_TOTAL -> R.string.history_payment
            else -> R.string.history_abono
        },
    )
    val amount = formatSignedCop(entry.amountCop, positive = !isPayment)
    val balanceAfter = formatCop(row.balanceAfterCop)
    val spoken = stringResource(
        when {
            !isPayment -> R.string.history_a11y_fiado
            entry.origin == MovementOrigin.PAGO_TOTAL -> R.string.history_a11y_payment
            else -> R.string.history_a11y_abono
        },
        formatCop(entry.amountCop).removePrefix("$"),
        formatDayLong(entry.date),
        balanceAfter.removePrefix("$"),
    ) + entry.description?.let { " " + stringResource(R.string.history_a11y_note, it) }.orEmpty()

    // El abono se distingue por ✓, texto, signo menos y un fondo suave, no solo por el color.
    val tint = if (isPayment) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxWidth()
            .background(tint)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(formatDayShort(entry.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(
                amount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isPayment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        entry.description?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(R.string.history_balance_after, balanceAfter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Datos completos del cliente. No depende del historial: se abre aunque este esté desplazado. */
@Composable
private fun ClientInfoDialog(client: ClientEntity, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val phoneLabel = stringResource(R.string.detail_phone)
    var copied by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.info_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoField(stringResource(R.string.info_name)) { Text(client.name, style = MaterialTheme.typography.bodyLarge) }
                InfoField(phoneLabel) {
                    val phone = client.phone
                    if (phone == null) {
                        Text(stringResource(R.string.info_no_phone), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        // El botón va debajo del número: con texto grande, en la misma fila el número se partía en columnas.
                        Text(phone, style = MaterialTheme.typography.bodyLarge)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    // Solo el número guardado, sin etiquetas.
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(phoneLabel, phone)))
                                    // Android 13+ ya muestra su propia confirmación del portapapeles.
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) copied = true
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.info_copy_phone)) }
                        if (copied) {
                            Text(
                                stringResource(R.string.info_phone_copied),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
                InfoField(stringResource(R.string.info_note_label)) {
                    Text(client.note ?: stringResource(R.string.info_no_note), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    stringResource(
                        R.string.detail_created,
                        formatDayNumeric(Instant.ofEpochMilli(client.createdAtMillis).atZone(BusinessCalendar.ZONE).toLocalDate()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.info_close)) }
        },
    )
}

@Composable
private fun InfoField(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

@Composable
private fun PayFullDialogs(state: PayFullState, clientName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        PayFullState.Hidden -> Unit
        is PayFullState.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pay_full_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.pay_full_text, formatCop(state.amountCop), clientName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.pay_full_note), style = MaterialTheme.typography.bodyMedium)
                    state.failure?.let { failure ->
                        Text(
                            stringResource(
                                when (failure) {
                                    PayFullFailure.SAVE_FAILED -> R.string.pay_full_error_save
                                    PayFullFailure.REVIEW_DATES -> R.string.pay_full_error_dates
                                    PayFullFailure.UNAVAILABLE -> R.string.pay_full_error_unavailable
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onConfirm, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (state.saving) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.pay_full_saving))
                        }
                    } else {
                        Text(stringResource(R.string.pay_full_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pay_full_cancel))
                }
            },
        )
        is PayFullState.BalanceChanged -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pay_full_changed_title)) },
            text = {
                Text(
                    if (state.currentBalanceCop > 0) {
                        stringResource(R.string.pay_full_changed_owes, clientName, formatCop(state.currentBalanceCop))
                    } else {
                        stringResource(R.string.pay_full_changed_paid, clientName)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pay_full_changed_review))
                }
            },
        )
        is PayFullState.TooLarge -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pay_full_too_large_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.pay_full_too_large_text,
                        formatCop(state.balanceCop),
                        formatCop(MovementRules.AMOUNT_MAX),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pay_full_too_large_ok))
                }
            },
        )
    }
}
