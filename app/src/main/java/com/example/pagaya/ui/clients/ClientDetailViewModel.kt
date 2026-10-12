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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface ClientDetailState {
    data object Loading : ClientDetailState

    data class Loaded(val account: ClientWithAccount) : ClientDetailState

    data object NotFound : ClientDetailState

    data object Error : ClientDetailState
}

class ClientDetailViewModel(
    savedState: SavedStateHandle,
    private val repository: ClientRepository,
) : ViewModel() {
    private val clientId: String = checkNotNull(savedState[ClientFormViewModel.CLIENT_ID_ARG])
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow<ClientDetailState>(ClientDetailState.Loading)
    val state: StateFlow<ClientDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            retries.collectLatest {
                try {
                    repository.observeClient(clientId).collect { account ->
                        _state.value = account?.let(ClientDetailState::Loaded) ?: ClientDetailState.NotFound
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ClientDetailState.Error
                }
            }
        }
    }

    fun retry() {
        _state.value = ClientDetailState.Loading
        retries.value++
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ClientDetailViewModel(createSavedStateHandle(), pagaYaContainer().clientRepository) }
        }
    }
}
