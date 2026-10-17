package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface ClientListState {
    data object Loading : ClientListState

    data class Loaded(val clients: List<ClientWithAccount>) : ClientListState

    data object Error : ClientListState
}

/**
 * Lista de clientes con buscador. [archived] elige la vista: activos (la lista principal) o archivados;
 * nunca se mezclan (HU-06).
 */
class ClientListViewModel(
    private val savedState: SavedStateHandle,
    private val repository: ClientRepository,
    private val archived: Boolean = false,
) : ViewModel() {

    /** Se conserva al navegar y si el sistema recrea el proceso. */
    val query: StateFlow<String> = savedState.getStateFlow(QUERY_KEY, "")

    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow<ClientListState>(ClientListState.Loading)
    val state: StateFlow<ClientListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query, retries) { text, _ -> text }.collectLatest { text ->
                try {
                    val clients = if (archived) repository.observeArchivedClients(text) else repository.observeActiveClients(text)
                    clients.collect { _state.value = ClientListState.Loaded(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ClientListState.Error
                }
            }
        }
    }

    fun onQueryChange(text: String) {
        savedState[QUERY_KEY] = text
    }

    fun retry() {
        _state.value = ClientListState.Loading
        retries.value++
    }

    companion object {
        private const val QUERY_KEY = "query"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ClientListViewModel(createSavedStateHandle(), pagaYaContainer().clientRepository) }
        }

        val ArchivedFactory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ClientListViewModel(createSavedStateHandle(), pagaYaContainer().clientRepository, archived = true) }
        }
    }
}
