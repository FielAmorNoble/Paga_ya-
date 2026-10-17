package com.example.pagaya.ui.clients

import com.example.pagaya.ui.common.writeEpoch
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.DescriptionError
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementInput
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.MovementValidation
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

enum class MovementFormFailure { SAVE_FAILED, CONFLICT, CLIENT_NOT_FOUND, CLIENT_ARCHIVED }

data class MovementFormState(
    val type: MovementType,
    /** Cuenta del cliente; `null` mientras carga. */
    val account: ClientWithAccount? = null,
    val amountText: String = "",
    val date: LocalDate,
    val description: String = "",
    val amountError: AmountError? = null,
    val dateError: DateError? = null,
    val descriptionError: DescriptionError? = null,
    /** Motivo por el que el historial no admite el movimiento; su lugar en pantalla depende del motivo. */
    val rejection: HistoryRejection? = null,
    val saving: Boolean = false,
    val failure: MovementFormFailure? = null,
    val savedMovementId: String? = null,
    val isDirty: Boolean = false,
)

/**
 * Registrar un FIADO o un ABONO para un cliente. Lo escrito, la fecha y el ID de solicitud se
 * guardan en [SavedStateHandle]: sobreviven a rotaciones y a la recreación del proceso, y todos los
 * reintentos de este formulario reutilizan el mismo ID, así que el repositorio no duplica el
 * movimiento. Un formulario nuevo genera otro ID: dos registros intencionales iguales sí coexisten.
 */
class MovementFormViewModel(
    private val saved: SavedStateHandle,
    private val clients: ClientRepository,
    private val movements: MovementRepository,
) : ViewModel() {
    private val epoch = writeEpoch(saved, movements::currentEpoch)

    private val clientId: String = checkNotNull(saved[CLIENT_ID_ARG])
    private val type: MovementType = MovementType.valueOf(checkNotNull(saved[TYPE_ARG]))
    private val requestId: String = saved[REQUEST_ID] ?: UUID.randomUUID().toString().also { saved[REQUEST_ID] = it }
    private val initialDate: LocalDate = LocalDate.parse(
        saved[INITIAL_DATE] ?: movements.today().toString().also { saved[INITIAL_DATE] = it },
    )

    private val _state = MutableStateFlow(
        MovementFormState(
            type = type,
            amountText = saved[AMOUNT] ?: "",
            date = LocalDate.parse(saved[DATE] ?: initialDate.toString()),
            description = saved[DESCRIPTION] ?: "",
        ).withDirtyFlag(),
    )
    val state: StateFlow<MovementFormState> = _state.asStateFlow()

    /** Fecha máxima admitida: hoy en America/Bogota. */
    val today: LocalDate get() = movements.today()

    init {
        viewModelScope.launch {
            try {
                clients.observeClient(clientId).collect { account ->
                    _state.update {
                        it.copy(
                            account = account,
                            failure = if (account == null) MovementFormFailure.CLIENT_NOT_FOUND else it.failure,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failure = MovementFormFailure.SAVE_FAILED) }
            }
        }
    }

    fun onAmountChange(value: String) = edit(AMOUNT, value) { copy(amountText = value, amountError = null) }

    fun onDateChange(value: LocalDate) = edit(DATE, value.toString()) { copy(date = value, dateError = null) }

    fun onDescriptionChange(value: String) = edit(DESCRIPTION, value) { copy(description = value, descriptionError = null) }

    fun save() {
        val current = _state.value
        // Se marca "guardando" de forma síncrona: un doble toque no lanza dos guardados.
        if (current.saving || current.account == null || current.savedMovementId != null) return
        val input = MovementInput(current.amountText, current.date, current.description)
        val validation = MovementRules.validate(input, today)
        if (validation is MovementValidation.Invalid) {
            _state.update { it.withErrors(validation) }
            return
        }
        _state.update { it.copy(saving = true, failure = null, rejection = null) }
        viewModelScope.launch {
            val result = try {
                movements.register(requestId, clientId, type, input, epoch.await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update {
                when (result) {
                    null -> it.copy(saving = false, failure = MovementFormFailure.SAVE_FAILED)
                    is RegisterMovementResult.Saved -> it.copy(saving = false, savedMovementId = result.movementId, isDirty = false)
                    is RegisterMovementResult.Invalid -> it.copy(saving = false).withErrors(result.errors)
                    is RegisterMovementResult.Rejected -> it.copy(saving = false, rejection = result.reason)
                    RegisterMovementResult.Conflict -> it.copy(saving = false, failure = MovementFormFailure.CONFLICT)
                    RegisterMovementResult.ClientNotFound -> it.copy(saving = false, failure = MovementFormFailure.CLIENT_NOT_FOUND)
                    RegisterMovementResult.ClientArchived -> it.copy(saving = false, failure = MovementFormFailure.CLIENT_ARCHIVED)
                    // Solo las produce el pago completo.
                    is RegisterMovementResult.BalanceChanged, is RegisterMovementResult.ExceedsLimit ->
                        it.copy(saving = false, failure = MovementFormFailure.SAVE_FAILED)
                }
            }
        }
    }

    private inline fun edit(key: String, value: String, change: MovementFormState.() -> MovementFormState) {
        if (_state.value.saving || _state.value.savedMovementId != null) return
        saved[key] = value
        _state.update { it.change().copy(failure = null, rejection = null).withDirtyFlag() }
    }

    private fun MovementFormState.withDirtyFlag() = copy(
        isDirty = amountText.isNotBlank() || description.isNotBlank() || date != initialDate,
    )

    private fun MovementFormState.withErrors(errors: MovementValidation.Invalid) =
        copy(amountError = errors.amount, dateError = errors.date, descriptionError = errors.description)

    companion object {
        const val CLIENT_ID_ARG = ClientFormViewModel.CLIENT_ID_ARG
        const val TYPE_ARG = "type"
        private const val REQUEST_ID = "requestId"
        private const val AMOUNT = "amount"
        private const val DATE = "date"
        private const val INITIAL_DATE = "initialDate"
        private const val DESCRIPTION = "description"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = pagaYaContainer()
                MovementFormViewModel(createSavedStateHandle(), container.clientRepository, container.movementRepository)
            }
        }
    }
}
