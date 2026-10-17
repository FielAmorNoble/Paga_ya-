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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.example.pagaya.ui.common.ScreenHeader

@Composable
fun ClientListScreen(
    onOpenClient: (String) -> Unit,
    onNewClient: () -> Unit,
    onOpenArchived: () -> Unit,
    /** Nombre del cliente que la cuenta acaba de archivar; `null` si no hay aviso. */
    archivedClient: String?,
    onArchivedClientShown: () -> Unit,
    viewModel: ClientListViewModel = viewModel(factory = ClientListViewModel.Factory),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ArchivedClientNotice(archivedClient, onArchivedClientShown, snackbarHostState)

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(top = 8.dp).semantics { heading() },
                )
                ListMenu(onOpenArchived)
            }
            SearchField(query, viewModel::onQueryChange)
            ClientListBody(
                state = state,
                onRetry = viewModel::retry,
                onOpenClient = onOpenClient,
                empty = {
                    if (query.isBlank()) {
                        CenteredMessage {
                            Text(
                                stringResource(R.string.clients_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            OutlinedButton(onClick = onNewClient, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.clients_empty_action))
                            }
                        }
                    } else {
                        NoResults(onClear = { viewModel.onQueryChange("") })
                    }
                },
            )
        }
    }
}

/** Menú secundario de la lista (docs/06): hoy solo «Archivados». */
@Composable
private fun ListMenu(onOpenArchived: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.archived_title)) },
                onClick = {
                    expanded = false
                    onOpenArchived()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Clientes archivados: misma búsqueda y orden que la lista principal, sin crear clientes. */
@Composable
fun ArchivedClientListScreen(
    onOpenClient: (String) -> Unit,
    onBack: () -> Unit,
    /** Una cuenta abierta desde aquí, reactivada y archivada de nuevo, vuelve aquí con este aviso. */
    archivedClient: String?,
    onArchivedClientShown: () -> Unit,
    viewModel: ClientListViewModel = viewModel(factory = ClientListViewModel.ArchivedFactory),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ArchivedClientNotice(archivedClient, onArchivedClientShown, snackbarHostState)

    Scaffold(
        topBar = { ScreenHeader(title = stringResource(R.string.archived_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Text(
                stringResource(R.string.archived_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SearchField(query, viewModel::onQueryChange)
            ClientListBody(
                state = state,
                onRetry = viewModel::retry,
                onOpenClient = onOpenClient,
                reserveNavigationBar = true,
                empty = {
                    if (query.isBlank()) {
                        CenteredMessage {
                            Text(
                                stringResource(R.string.archived_empty),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        NoResults(onClear = { viewModel.onQueryChange("") })
                    }
                },
            )
        }
    }
}

/** Confirmación de que la cuenta recién cerrada quedó archivada. */
@Composable
private fun ArchivedClientNotice(archivedClient: String?, onShown: () -> Unit, snackbarHostState: SnackbarHostState) {
    val message = archivedClient?.let { stringResource(R.string.archive_done, it) }
    // Al consumir el aviso cambia la clave del efecto; el snackbar va en un scope que no se cancela por eso.
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        if (message != null) {
            onShown()
            scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long) }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.clients_search_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Search,
        ),
        trailingIcon = if (query.isNotEmpty()) {
            { TextButton(onClick = { onQueryChange("") }) { Text(stringResource(R.string.clients_search_clear)) } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun ClientListBody(
    state: ClientListState,
    onRetry: () -> Unit,
    onOpenClient: (String) -> Unit,
    empty: @Composable () -> Unit,
    reserveNavigationBar: Boolean = false,
) {
    when (state) {
        ClientListState.Loading -> CenteredMessage {
            CircularProgressIndicator()
            Text(stringResource(R.string.loading))
        }
        ClientListState.Error -> CenteredMessage {
            Text(stringResource(R.string.clients_load_error), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        }
        is ClientListState.Loaded ->
            if (state.clients.isNotEmpty()) ClientList(state.clients, onOpenClient, reserveNavigationBar) else empty()
    }
}

@Composable
private fun NoResults(onClear: () -> Unit) {
    CenteredMessage {
        Text(stringResource(R.string.clients_no_results), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.clients_search_clear))
        }
    }
}

@Composable
private fun ClientList(clients: List<ClientWithAccount>, onOpenClient: (String) -> Unit, reserveNavigationBar: Boolean) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(clients, key = { it.client.id }) { account ->
            ClientRow(account, onClick = { onOpenClient(account.client.id) })
            HorizontalDivider()
        }
        if (reserveNavigationBar) item(key = "nav-space") { Spacer(Modifier.navigationBarsPadding()) }
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
