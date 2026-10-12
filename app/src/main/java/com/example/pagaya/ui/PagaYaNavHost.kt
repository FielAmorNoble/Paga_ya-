package com.example.pagaya.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pagaya.ui.clients.ClientDetailScreen
import com.example.pagaya.ui.clients.ClientFormScreen
import com.example.pagaya.ui.clients.ClientFormViewModel
import com.example.pagaya.ui.clients.ClientListScreen

// Rutas por ID; nunca se transportan entidades completas (docs/04).
private object Routes {
    const val CLIENTS = "clients"
    const val NEW_CLIENT = "clients/new"
    const val CLIENT = "clients/{${ClientFormViewModel.CLIENT_ID_ARG}}"
    const val EDIT_CLIENT = "clients/{${ClientFormViewModel.CLIENT_ID_ARG}}/edit"

    fun client(id: String) = "clients/$id"
    fun editClient(id: String) = "clients/$id/edit"
}

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
        composable(Routes.CLIENT, arguments = clientIdArg) {
            ClientDetailScreen(
                onEdit = { navController.navigate(Routes.editClient(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EDIT_CLIENT, arguments = clientIdArg) {
            ClientFormScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
