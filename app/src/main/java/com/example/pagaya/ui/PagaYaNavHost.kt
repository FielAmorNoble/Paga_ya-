package com.example.pagaya.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.ui.clients.ClientDetailScreen
import com.example.pagaya.ui.clients.ClientFormScreen
import com.example.pagaya.ui.clients.ClientFormViewModel
import com.example.pagaya.ui.clients.ClientListScreen
import com.example.pagaya.ui.clients.MovementFormScreen
import com.example.pagaya.ui.clients.MovementFormViewModel

// Rutas por ID; nunca se transportan entidades completas (docs/04).
private object Routes {
    const val CLIENTS = "clients"
    const val NEW_CLIENT = "clients/new"
    const val CLIENT = "clients/{${ClientFormViewModel.CLIENT_ID_ARG}}"
    const val EDIT_CLIENT = "clients/{${ClientFormViewModel.CLIENT_ID_ARG}}/edit"
    const val NEW_MOVEMENT = "clients/{${MovementFormViewModel.CLIENT_ID_ARG}}/movements/{${MovementFormViewModel.TYPE_ARG}}"

    fun client(id: String) = "clients/$id"
    fun editClient(id: String) = "clients/$id/edit"
    fun newMovement(id: String, type: MovementType) = "clients/$id/movements/${type.name}"
}

/** Clave con la que el formulario de movimiento avisa a la cuenta que acaba de guardar (el tipo guardado). */
private const val SAVED_MOVEMENT = "savedMovement"

@Composable
fun PagaYaNavHost() {
    val navController = rememberNavController()
    val clientIdArg = listOf(navArgument(ClientFormViewModel.CLIENT_ID_ARG) { type = NavType.StringType })

    NavHost(navController = navController, startDestination = Routes.CLIENTS) {
        composable(Routes.CLIENTS) {
            ClientListScreen(
                onOpenClient = { navController.navigate(Routes.client(it)) },
                onNewClient = { navController.navigate(Routes.NEW_CLIENT) },
            )
        }
        composable(Routes.NEW_CLIENT) {
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
                onBack = { navController.popBackStack() },
                savedMovement = savedMovement,
                onSavedMovementShown = { entry.savedStateHandle[SAVED_MOVEMENT] = null },
            )
        }
        composable(Routes.EDIT_CLIENT, arguments = clientIdArg) {
            ClientFormScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
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
