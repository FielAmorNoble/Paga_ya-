package com.example.pagaya.ui.clients

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.ui.common.accountStatusText

@Composable
fun ClientListScreen(
    onOpenClient: (String) -> Unit,
    onNewClient: () -> Unit,
    viewModel: ClientListViewModel = viewModel(factory = ClientListViewModel.Factory),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Button(
                onClick = onNewClient,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.clients_new), style = MaterialTheme.typography.titleMedium)
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics { heading() },
            )
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.clients_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Search,
                ),
                trailingIcon = if (query.isNotEmpty()) {
                    { TextButton(onClick = { viewModel.onQueryChange("") }) { Text(stringResource(R.string.clients_search_clear)) } }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            when (val current = state) {
                ClientListState.Loading -> CenteredMessage {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading))
                }
                ClientListState.Error -> CenteredMessage {
                    Text(stringResource(R.string.clients_load_error), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                is ClientListState.Loaded -> when {
                    current.clients.isNotEmpty() -> ClientList(current.clients, onOpenClient)
                    query.isBlank() -> CenteredMessage {
                        Text(
                            stringResource(R.string.clients_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(onClick = onNewClient, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.clients_empty_action))
                        }
                    }
                    else -> CenteredMessage {
                        Text(stringResource(R.string.clients_no_results), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { viewModel.onQueryChange("") }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.clients_search_clear))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientList(clients: List<ClientWithAccount>, onOpenClient: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(clients, key = { it.client.id }) { account ->
            ClientRow(account, onClick = { onOpenClient(account.client.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ClientRow(account: ClientWithAccount, onClick: () -> Unit) {
    // La fila entera es un único elemento accesible que abre la cuenta.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(account.client.name, style = MaterialTheme.typography.titleMedium)
        // El teléfono ayuda a distinguir homónimos.
        account.client.phone?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(accountStatusText(account), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}
