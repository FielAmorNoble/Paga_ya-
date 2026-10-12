package com.example.pagaya.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.accountStatusText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BUSINESS_ZONE: ZoneId = ZoneId.of("America/Bogota")
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun ClientDetailScreen(
    onEdit: (clientId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ClientDetailViewModel = viewModel(factory = ClientDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = (state as? ClientDetailState.Loaded)?.account?.client?.name.orEmpty()

    Scaffold(topBar = { ScreenHeader(title = title, onBack = onBack) }) { innerPadding ->
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
                is ClientDetailState.Loaded -> ClientDetail(current.account, onEdit = { onEdit(current.account.client.id) })
            }
        }
    }
}

@Composable
private fun ClientDetail(account: ClientWithAccount, onEdit: () -> Unit) {
    val client = account.client
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.detail_account),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(accountStatusText(account), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.detail_movements_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        client.phone?.let { LabeledValue(stringResource(R.string.detail_phone), it) }
        client.note?.let { LabeledValue(stringResource(R.string.detail_note), it) }
        Text(
            stringResource(
                R.string.detail_created,
                DAY_FORMAT.format(Instant.ofEpochMilli(client.createdAtMillis).atZone(BUSINESS_ZONE)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.detail_edit), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
