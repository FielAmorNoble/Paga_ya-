package com.example.pagaya.ui.backup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.backup.BackupFailure
import com.example.pagaya.data.backup.BackupService
import com.example.pagaya.data.backup.BackupStatus
import com.example.pagaya.data.backup.ExportPhase
import com.example.pagaya.data.backup.ExportResult
import com.example.pagaya.data.backup.PrepareResult
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Operación de copia en curso o su resultado. */
sealed interface BackupOperation {
    data object Idle : BackupOperation

    data object Preparing : BackupOperation

    /** Selector del sistema abierto (o por abrir) para elegir dónde guardar [fileName]. */
    data class ChoosingDestination(val fileName: String) : BackupOperation

    data object Writing : BackupOperation

    data object Verifying : BackupOperation

    /** Copia escrita, releída y validada. */
    data class Saved(val exportedAtMillis: Long) : BackupOperation

    data class Failed(val failure: BackupFailure, val canRetry: Boolean) : BackupOperation

    val busy: Boolean get() = this is Preparing || this is ChoosingDestination || this is Writing || this is Verifying
}

sealed interface StatusState {
    data object Loading : StatusState

    data class Loaded(val status: BackupStatus) : StatusState

    data object Error : StatusState
}

data class BackupUiState(
    val status: StatusState = StatusState.Loading,
    val operation: BackupOperation = BackupOperation.Idle,
    /** Nombre sugerido para abrir el selector; la pantalla lo consume una sola vez. */
    val pickerRequest: String? = null,
)

/**
 * Pantalla «Respaldo». La captura pendiente y la fase se guardan en [SavedStateHandle]: si el sistema
 * cierra el proceso mientras el selector está abierto, al volver se continúa con esa captura; si se cerró
 * mientras se escribía o verificaba, se informa que no terminó (nunca queda «Preparando» ni un éxito
 * sin verificar).
 */
class BackupViewModel(
    private val savedState: SavedStateHandle,
    private val service: BackupService,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    private val cleanup: Job

    init {
        val pendingId = savedState.get<String>(PENDING_ID)
        val phase = savedState.get<String>(PHASE)
        val fileName = savedState.get<String>(FILE_NAME)
        when {
            pendingId != null && phase == PHASE_CHOOSING && fileName != null ->
                _state.update { it.copy(operation = BackupOperation.ChoosingDestination(fileName)) }
            pendingId != null && phase == PHASE_EXPORTING -> {
                clearPending()
                _state.update { it.copy(operation = BackupOperation.Failed(BackupFailure.INTERRUPTED, canRetry = false)) }
            }
            else -> clearPending()
        }
        cleanup = viewModelScope.launch {
            try {
                service.recoverInterrupted(keepBackupId = savedState.get<String>(PENDING_ID))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La limpieza se reintenta en el próximo inicio; no impide guardar una copia.
            }
        }
        viewModelScope.launch {
            try {
                service.observeStatus().collect { status -> _state.update { it.copy(status = StatusState.Loaded(status)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = StatusState.Error) }
            }
        }
    }

    /** «Guardar copia en archivo»: siempre una captura nueva. Ignora toques mientras hay una operación. */
    fun onSave() {
        if (_state.value.operation.busy) return
        _state.update { it.copy(operation = BackupOperation.Preparing) }
        viewModelScope.launch {
            cleanup.join()
            when (val result = service.prepare()) {
                is PrepareResult.Ready -> {
                    savedState[PENDING_ID] = result.backupId
                    savedState[FILE_NAME] = result.fileName
                    savedState[PHASE] = PHASE_CHOOSING
                    _state.update {
                        it.copy(operation = BackupOperation.ChoosingDestination(result.fileName), pickerRequest = result.fileName)
                    }
                }
                PrepareResult.Busy -> _state.update { it.copy(operation = BackupOperation.Idle) }
                is PrepareResult.Failed -> _state.update { it.copy(operation = BackupOperation.Failed(result.failure, canRetry = false)) }
            }
        }
    }

    /** Guardar la misma captura en otra ubicación tras un fallo de escritura o de verificación. */
    fun onRetry() {
        val operation = _state.value.operation
        val fileName = savedState.get<String>(FILE_NAME)
        if (operation !is BackupOperation.Failed || !operation.canRetry || fileName == null || savedState.get<String>(PENDING_ID) == null) return
        savedState[PHASE] = PHASE_CHOOSING
        _state.update { it.copy(operation = BackupOperation.ChoosingDestination(fileName), pickerRequest = fileName) }
    }

    fun onPickerLaunched() {
        _state.update { it.copy(pickerRequest = null) }
    }

    /** Resultado del selector: `null` si la usuaria lo cerró sin elegir (ni éxito ni error). */
    fun onDestinationChosen(location: String?) {
        val backupId = savedState.get<String>(PENDING_ID) ?: return
        if (location == null) {
            clearPending()
            _state.update { it.copy(operation = BackupOperation.Idle) }
            viewModelScope.launch {
                try {
                    service.cancel(backupId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // El intento queda «Preparando» y se cierra en el próximo inicio.
                }
            }
            return
        }
        savedState[PHASE] = PHASE_EXPORTING
        _state.update { it.copy(operation = BackupOperation.Writing) }
        viewModelScope.launch {
            val result = try {
                service.export(backupId, location) { phase ->
                    val operation = if (phase == ExportPhase.WRITING) BackupOperation.Writing else BackupOperation.Verifying
                    _state.update { it.copy(operation = operation) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportResult.Failed(BackupFailure.VERIFICATION, canRetry = false)
            }
            when (result) {
                is ExportResult.Verified -> {
                    clearPending()
                    _state.update { it.copy(operation = BackupOperation.Saved(result.exportedAtMillis)) }
                }
                is ExportResult.Failed -> {
                    if (result.canRetry) savedState[PHASE] = PHASE_FAILED else clearPending()
                    _state.update { it.copy(operation = BackupOperation.Failed(result.failure, result.canRetry)) }
                }
                ExportResult.Busy -> {
                    clearPending()
                    _state.update { it.copy(operation = BackupOperation.Idle) }
                }
            }
        }
    }

    /** Cierra el aviso de éxito o de error. El estado de la última copia verificada sigue a la vista. */
    fun onMessageShown() {
        val operation = _state.value.operation
        if (operation is BackupOperation.Saved || operation is BackupOperation.Failed) {
            _state.update { it.copy(operation = BackupOperation.Idle) }
        }
    }

    private fun clearPending() {
        savedState.remove<String>(PENDING_ID)
        savedState.remove<String>(FILE_NAME)
        savedState.remove<String>(PHASE)
    }

    companion object {
        private const val PENDING_ID = "backupPendingId"
        private const val FILE_NAME = "backupFileName"
        private const val PHASE = "backupPhase"
        private const val PHASE_CHOOSING = "choosing"
        private const val PHASE_EXPORTING = "exporting"
        private const val PHASE_FAILED = "failed"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupViewModel(createSavedStateHandle(), pagaYaContainer().backupService) }
        }
    }
}
