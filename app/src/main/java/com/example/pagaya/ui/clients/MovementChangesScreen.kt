package com.example.pagaya.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.example.pagaya.data.local.RevisionAction
import com.example.pagaya.domain.MovementChange
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.formatCop
import com.example.pagaya.ui.common.formatDateTime
import com.example.pagaya.ui.common.formatDayShort

/** «Ver cambios»: valores anteriores y posteriores, motivo y fecha de cada corrección o anulación. */
@Composable
fun MovementChangesScreen(
    onBack: () -> Unit,
    viewModel: MovementChangesViewModel = viewModel(factory = MovementChangesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { ScreenHeader(title = stringResource(R.string.changes_title), onBack = onBack) }) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                MovementChangesState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                MovementChangesState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.changes_error), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                is MovementChangesState.Loaded ->
                    if (current.changes.isEmpty()) {
                        Text(stringResource(R.string.changes_empty), Modifier.align(Alignment.Center).padding(24.dp))
                    } else {
                        ChangeList(current.changes)
                    }
            }
        }
    }
}

@Composable
private fun ChangeList(changes: List<MovementChange>) {
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(changes, key = { index, _ -> index }) { _, change -> ChangeCard(change) }
        item(key = "original") { OriginalCard(changes.last()) }
    }
}

@Composable
private fun ChangeCard(change: MovementChange) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (change.action == RevisionAction.ANULAR) R.string.changes_annulment else R.string.changes_correction),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(formatDateTime(change.changedAtMillis), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            change.changes.forEach { Text(fieldChangeText(it), style = MaterialTheme.typography.bodyLarge) }
            Text(stringResource(R.string.changes_reason, change.reason), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** El registro tal como se hizo la primera vez: lo que había antes de la primera corrección. */
@Composable
private fun OriginalCard(oldest: MovementChange) {
    val original = oldest.before
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.changes_original_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(formatDateTime(original.createdAtMillis), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.changes_original_amount, formatCop(original.amountCop)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.changes_original_date, formatDayShort(original.date)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.changes_original_note, quotedNote(original.description)), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
