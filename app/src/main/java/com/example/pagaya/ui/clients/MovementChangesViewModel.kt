package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.domain.MovementChange
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface MovementChangesState {
    data object Loading : MovementChangesState

    /** [changes] va del cambio más reciente al más antiguo; [original] es el registro tal como se hizo. */
    data class Loaded(val changes: List<MovementChange>) : MovementChangesState

    /** Las revisiones o un snapshot están dañados: se informa en lugar de mostrar un historial engañoso. */
    data object Error : MovementChangesState
}

class MovementChangesViewModel(
    savedState: SavedStateHandle,
    private val movements: MovementRepository,
) : ViewModel() {
    private val movementId: String = checkNotNull(savedState[MovementDetailViewModel.MOVEMENT_ID_ARG])
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow<MovementChangesState>(MovementChangesState.Loading)
    val state: StateFlow<MovementChangesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            retries.collectLatest {
                try {
                    movements.observeChanges(movementId).collect { _state.value = MovementChangesState.Loaded(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = MovementChangesState.Error
                }
            }
        }
    }

    fun retry() {
        _state.value = MovementChangesState.Loading
        retries.value++
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MovementChangesViewModel(createSavedStateHandle(), pagaYaContainer().movementRepository) }
        }
    }
}
