package com.example.pagaya.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.data.LedgerCoordination
import com.example.pagaya.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.ui.backup.BackupScreen
import com.example.pagaya.ui.clients.ArchivedClientListScreen
import com.example.pagaya.ui.clients.ClientDetailScreen
import com.example.pagaya.ui.clients.ClientFormScreen
import com.example.pagaya.ui.clients.ClientFormViewModel
import com.example.pagaya.ui.clients.ClientListScreen
import com.example.pagaya.ui.clients.MovementChangesScreen
import com.example.pagaya.ui.clients.MovementDetailScreen
import com.example.pagaya.ui.clients.MovementDetailViewModel
import com.example.pagaya.ui.clients.MovementEditScreen
import com.example.pagaya.ui.clients.MovementFormScreen
import com.example.pagaya.ui.clients.SAVED_CORRECTION
import com.example.pagaya.ui.clients.MovementFormViewModel

// Rutas por ID; nunca se transportan entidades completas (docs/04).
private object Routes {
    const val CLIENTS = "clients"
    const val NEW_CLIENT = "clients/new"
    const val ARCHIVED = "clients/archived"
    const val BACKUP = "backup"
    const val CLIENT = "clients/{${ClientFormViewModel.CLIENT_ID_ARG}}"
    const val EDIT_CLIENT = "clients/{${ClientFormViewModel.CLIENT_ID_ARG}}/edit"
    const val MOVEMENT = "movement/{${MovementDetailViewModel.MOVEMENT_ID_ARG}}"
    const val EDIT_MOVEMENT = "movement/{${MovementDetailViewModel.MOVEMENT_ID_ARG}}/edit"
    const val MOVEMENT_CHANGES = "movement/{${MovementDetailViewModel.MOVEMENT_ID_ARG}}/changes"
    const val NEW_MOVEMENT = "clients/{${MovementFormViewModel.CLIENT_ID_ARG}}/movements/{${MovementFormViewModel.TYPE_ARG}}"

    fun client(id: String) = "clients/$id"
    fun editClient(id: String) = "clients/$id/edit"
    fun movement(id: String) = "movement/$id"
    fun editMovement(id: String) = "movement/$id/edit"
    fun movementChanges(id: String) = "movement/$id/changes"
    fun newMovement(id: String, type: MovementType) = "clients/$id/movements/${type.name}"
}

/** Clave con la que el formulario de movimiento avisa a la cuenta que acaba de guardar (el tipo guardado). */
private const val SAVED_MOVEMENT = "savedMovement"

/** Clave con la que la cuenta avisa a la lista de origen que acaba de archivar al cliente (su nombre). */
private const val ARCHIVED_CLIENT = "archivedClient"

@Composable
fun PagaYaNavHost(session: LedgerSessionViewModel = viewModel(factory = LedgerSessionViewModel.Factory)) {
    val epoch by session.epoch.collectAsStateWithLifecycle()
    val notice by session.notice.collectAsStateWithLifecycle()
    val failed by session.failed.collectAsStateWithLifecycle()
    if (epoch != null) LedgerNavHost(epoch!!) else Text(stringResource(if (failed) R.string.restore_read_failed else R.string.loading))
    notice?.let { result -> AlertDialog(
        onDismissRequest = session::acknowledge,
        title = { Text(stringResource(if (result.committed) R.string.restore_completed else R.string.restore_interrupted_title)) },
        text = { Text(stringResource(if (result.committed) R.string.restore_completed_detail else R.string.restore_interrupted)) },
        confirmButton = { TextButton(onClick = session::acknowledge) { Text(stringResource(R.string.backup_ok)) } },
    ) }
}

@Composable
private fun LedgerNavHost(epoch: String) {
    val navController = rememberNavController()
    val epochArg = listOf(navArgument(LedgerCoordination.EPOCH_ARG) { type = NavType.StringType; defaultValue = epoch })
    val movementIdArg = epochArg + listOf(navArgument(MovementDetailViewModel.MOVEMENT_ID_ARG) { type = NavType.StringType })
    val clientIdArg = epochArg + listOf(navArgument(ClientFormViewModel.CLIENT_ID_ARG) { type = NavType.StringType })

    LaunchedEffect(epoch) {
        if (navController.currentBackStackEntry?.arguments?.getString(LedgerCoordination.EPOCH_ARG) != epoch) {
            navController.navigate(Routes.CLIENTS) { popUpTo(navController.graph.id) { inclusive = true }; launchSingleTop = true }
        }
    }
    key(epoch) {
    NavHost(navController = navController, startDestination = Routes.CLIENTS) {
        composable(Routes.CLIENTS, arguments = epochArg) { entry ->
            val archivedClient by entry.savedStateHandle.getStateFlow<String?>(ARCHIVED_CLIENT, null).collectAsStateWithLifecycle()
            ClientListScreen(
                onOpenClient = { navController.navigate(Routes.client(it)) },
                onNewClient = { navController.navigate(Routes.NEW_CLIENT) },
                onOpenArchived = { navController.navigate(Routes.ARCHIVED) { launchSingleTop = true } },
                onOpenBackup = { navController.navigate(Routes.BACKUP) { launchSingleTop = true } },
                archivedClient = archivedClient,
                onArchivedClientShown = { entry.savedStateHandle[ARCHIVED_CLIENT] = null },
            )
        }
        composable(Routes.ARCHIVED, arguments = epochArg) { entry ->
            val archivedClient by entry.savedStateHandle.getStateFlow<String?>(ARCHIVED_CLIENT, null).collectAsStateWithLifecycle()
            ArchivedClientListScreen(
                onOpenClient = { navController.navigate(Routes.client(it)) },
                onBack = { navController.popBackStack() },
                archivedClient = archivedClient,
                onArchivedClientShown = { entry.savedStateHandle[ARCHIVED_CLIENT] = null },
            )
        }
        composable(Routes.BACKUP, arguments = epochArg) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NEW_CLIENT, arguments = epochArg) {
            ClientFormScreen(
                // HU-02: al guardar se abre la cuenta recién creada; el formulario sale de la pila.
                onSaved = { id ->
                    navController.navigate(Routes.client(id)) { popUpTo(Routes.NEW_CLIENT) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CLIENT, arguments = clientIdArg) { entry ->
            val savedMovement by entry.savedStateHandle.getStateFlow<String?>(SAVED_MOVEMENT, null).collectAsStateWithLifecycle()
            ClientDetailScreen(
                onEdit = { navController.navigate(Routes.editClient(it)) },
                onNewMovement = { id, type -> navController.navigate(Routes.newMovement(id, type)) { launchSingleTop = true } },
                onOpenMovement = { navController.navigate(Routes.movement(it)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
                savedMovement = savedMovement,
                onSavedMovementShown = { entry.savedStateHandle[SAVED_MOVEMENT] = null },
                onArchived = { name ->
                    // La lista de origen (Clientes o Archivados) es la pantalla anterior; allí se confirma.
                    navController.previousBackStackEntry?.savedStateHandle?.set(ARCHIVED_CLIENT, name)
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.EDIT_CLIENT, arguments = clientIdArg) {
            ClientFormScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MOVEMENT, arguments = movementIdArg) { entry ->
            val message by entry.savedStateHandle.getStateFlow<String?>(SAVED_MOVEMENT, null).collectAsStateWithLifecycle()
            MovementDetailScreen(
                onCorrect = { navController.navigate(Routes.editMovement(it)) { launchSingleTop = true } },
                onViewChanges = { navController.navigate(Routes.movementChanges(it)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
                savedMessage = message,
                onSavedMessageShown = { entry.savedStateHandle[SAVED_MOVEMENT] = null },
            )
        }
        composable(Routes.EDIT_MOVEMENT, arguments = movementIdArg) {
            MovementEditScreen(
                onSaved = {
                    // El detalle es la pantalla anterior; allí se muestra «Corrección guardada».
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVED_MOVEMENT, SAVED_CORRECTION)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MOVEMENT_CHANGES, arguments = movementIdArg) {
            MovementChangesScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.NEW_MOVEMENT,
            arguments = clientIdArg + navArgument(MovementFormViewModel.TYPE_ARG) { type = NavType.StringType },
        ) {
            MovementFormScreen(
                onSaved = { type ->
                    // La cuenta es la pantalla anterior; allí se muestra «Fiado guardado» / «Abono guardado».
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVED_MOVEMENT, type.name)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
}
