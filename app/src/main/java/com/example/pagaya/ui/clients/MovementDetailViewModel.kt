package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.ChangeMovementResult
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.ReasonError
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface MovementDetailState {
    data object Loading : MovementDetailState

    data class Loaded(
        val movement: MovementEntity,
        val account: ClientWithAccount,
        val revisionCount: Int,
    ) : MovementDetailState

    data object NotFound : MovementDetailState

    data object Error : MovementDetailState
}

enum class AnnulFailure { SAVE_FAILED, VERSION_CONFLICT, ALREADY_ANNULLED, UNAVAILABLE }

/** Diálogo de anulación. */
sealed interface AnnulState {
    data object Hidden : AnnulState

    data class Open(
        val reason: String = "",
        val reasonError: ReasonError? = null,
        val rejection: HistoryRejection? = null,
        val failure: AnnulFailure? = null,
        val saving: Boolean = false,
    ) : AnnulState
}

/**
 * Detalle de un movimiento y su anulación. La versión que la usuaria vio al abrir el diálogo y el ID
 * de solicitud se fijan al abrirlo y se reutilizan en los reintentos: el repositorio no crea dos
 * revisiones ni sobrescribe un cambio hecho desde otra pantalla.
 */
class MovementDetailViewModel(
    savedState: SavedStateHandle,
    private val clients: ClientRepository,
    private val movements: MovementRepository,
) : ViewModel() {
    private val movementId: String = checkNotNull(savedState[MOVEMENT_ID_ARG])
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow<MovementDetailState>(MovementDetailState.Loading)
    val state: StateFlow<MovementDetailState> = _state.asStateFlow()

    private val _annul = MutableStateFlow<AnnulState>(AnnulState.Hidden)
    val annul: StateFlow<AnnulState> = _annul.asStateFlow()

    private val _annulled = MutableStateFlow(false)

    /** `true` desde que se anuló el movimiento hasta que la pantalla lo avisa con [consumeAnnulled]. */
    val annulled: StateFlow<Boolean> = _annulled.asStateFlow()

    private var annulRequestId = ""
    private var annulExpectedVersion = 0L

    init {
        viewModelScope.launch {
            retries.collectLatest {
                try {
                    loadedFlow().collect { _state.value = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = MovementDetailState.Error
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadedFlow() = movements.observeMovementDetail(movementId).flatMapLatest { detail ->
        if (detail == null) {
            flowOf(MovementDetailState.NotFound)
        } else {
            clients.observeClient(detail.movement.clientId).map { account ->
                if (account == null) MovementDetailState.NotFound else MovementDetailState.Loaded(detail.movement, account, detail.revisionCount)
            }
        }
    }

    fun retry() {
        _state.value = MovementDetailState.Loading
        retries.value++
    }

    fun consumeAnnulled() {
        _annulled.value = false
    }

    fun openAnnul() {
        val loaded = _state.value as? MovementDetailState.Loaded ?: return
        if (!loaded.movement.active || _annul.value != AnnulState.Hidden) return
        annulRequestId = UUID.randomUUID().toString()
        annulExpectedVersion = loaded.movement.version
        _annul.value = AnnulState.Open()
    }

    fun dismissAnnul() {
        if ((_annul.value as? AnnulState.Open)?.saving == true) return
        _annul.value = AnnulState.Hidden
    }

    fun onAnnulReasonChange(value: String) {
        _annul.update { current ->
            if (current is AnnulState.Open && !current.saving) {
                current.copy(reason = value, reasonError = null, rejection = null, failure = null)
            } else {
                current
            }
        }
    }

    fun confirmAnnul() {
        val current = _annul.value as? AnnulState.Open ?: return
        // Se marca «guardando» de forma síncrona: un doble toque no lanza dos anulaciones.
        if (current.saving) return
        MovementRules.validateReason(current.reason)?.let { error ->
            _annul.value = current.copy(reasonError = error)
            return
        }
        _annul.value = current.copy(saving = true, failure = null, rejection = null)
        viewModelScope.launch {
            val result = try {
                movements.annul(annulRequestId, movementId, annulExpectedVersion, current.reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                is ChangeMovementResult.Saved -> {
                    _annulled.value = true
                    _annul.value = AnnulState.Hidden
                }
                else -> _annul.update {
                    (it as? AnnulState.Open)?.copy(saving = false)?.withResult(result) ?: it
                }
            }
        }
    }

    private fun AnnulState.Open.withResult(result: ChangeMovementResult?): AnnulState.Open = when (result) {
        null -> copy(failure = AnnulFailure.SAVE_FAILED)
        is ChangeMovementResult.Rejected -> copy(rejection = result.reason)
        is ChangeMovementResult.Invalid -> copy(reasonError = result.errors.reason)
        ChangeMovementResult.VersionConflict -> copy(failure = AnnulFailure.VERSION_CONFLICT)
        ChangeMovementResult.AlreadyAnnulled -> copy(failure = AnnulFailure.ALREADY_ANNULLED)
        else -> copy(failure = AnnulFailure.UNAVAILABLE)
    }

    companion object {
        const val MOVEMENT_ID_ARG = "movementId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = pagaYaContainer()
                MovementDetailViewModel(createSavedStateHandle(), container.clientRepository, container.movementRepository)
            }
        }
    }
}
