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
import com.example.pagaya.data.CorrectionInvalid
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementOrigin
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.CorrectionInput
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.DescriptionError
import com.example.pagaya.domain.FieldChange
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.LedgerRules
import com.example.pagaya.domain.MovementInput
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.MovementSnapshot
import com.example.pagaya.domain.MovementValidation
import com.example.pagaya.domain.ReasonError
import com.example.pagaya.domain.changesTo
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

enum class EditFailure { SAVE_FAILED, VERSION_CONFLICT, ALREADY_ANNULLED, CONFLICT, CLIENT_ARCHIVED, NOT_FOUND }

/**
 * Lo que la usuaria revisó y lo que se guardará: una solicitud inmutable. La confirmación guarda
 * exactamente esto, no los campos del formulario en el momento de confirmar.
 */
data class CorrectionRequest(
    val amountText: String,
    val date: LocalDate,
    val description: String,
    val reason: String,
    /** La versión del movimiento que la usuaria vio al abrir la corrección. */
    val expectedVersion: Long,
)

/** Lo que cambiará, mostrado antes de guardar la corrección, junto con la solicitud exacta que se confirmará. */
data class CorrectionPreview(
    val request: CorrectionRequest,
    val changes: List<FieldChange>,
    val clientName: String,
    /** Saldo de la cuenta tras la corrección; `null` si no se pudo calcular. */
    val newBalanceCop: Long?,
) {
    val reason: String get() = request.reason.trim()
}

/** Resultado de la comprobación anticipada, solo informativa: el repositorio decide al guardar. */
data class CorrectionEvaluation(val rejection: HistoryRejection?, val newBalanceCop: Long?)

/** Comprueba por adelantado si la corrección dejaría el historial válido y qué saldo daría. */
fun interface CorrectionEvaluator {
    suspend fun evaluate(original: MovementEntity, origin: MovementOrigin, newAmountCop: Long, newDate: LocalDate): CorrectionEvaluation
}

/** Evaluador real: lee el historial de la cuenta y aplica las mismas reglas del repositorio. */
class HistoryCorrectionEvaluator(private val movements: MovementRepository) : CorrectionEvaluator {
    override suspend fun evaluate(
        original: MovementEntity,
        origin: MovementOrigin,
        newAmountCop: Long,
        newDate: LocalDate,
    ): CorrectionEvaluation = try {
        val entries = movements.observeHistory(original.clientId).first().map { it.entry }.filter { it.active }
        val changed = entries.first { it.id == original.id }.copy(amountCop = newAmountCop, date = newDate, origin = origin)
        val rejection = LedgerRules.rejectionForChange(entries, changed)
        CorrectionEvaluation(
            rejection,
            if (rejection == null) LedgerRules.balance(entries.map { if (it.id == original.id) changed else it }) else null,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CorrectionEvaluation(null, null)
    }
}

data class MovementEditState(
    val loading: Boolean = true,
    /** El movimiento tal como estaba al abrir la corrección. */
    val original: MovementEntity? = null,
    val clientName: String = "",
    val amountText: String = "",
    val date: LocalDate? = null,
    val description: String = "",
    val reason: String = "",
    val amountError: AmountError? = null,
    val dateError: DateError? = null,
    val descriptionError: DescriptionError? = null,
    val reasonError: ReasonError? = null,
    val rejection: HistoryRejection? = null,
    val failure: EditFailure? = null,
    val noChanges: Boolean = false,
    /** Evaluando la vista previa: el formulario está bloqueado hasta que termine. */
    val reviewing: Boolean = false,
    val preview: CorrectionPreview? = null,
    val saving: Boolean = false,
    val savedVersion: Long? = null,
    val isDirty: Boolean = false,
)

/**
 * Corregir valor, fecha y descripción de un movimiento vigente. Lo escrito, la versión que la usuaria
 * vio al abrir y el ID de solicitud se guardan en [SavedStateHandle]: sobreviven a la recreación del
 * proceso y un reintento reutiliza el mismo ID, así que el repositorio no crea dos revisiones.
 *
 * Revisar y confirmar forman un ciclo cerrado: al revisar se congela una [CorrectionRequest]; mientras
 * se evalúa, el formulario no admite cambios ni una segunda revisión, y la confirmación guarda esa
 * solicitud, no el estado actual de los campos. Un resultado que llega tarde (la evaluación ya fue
 * invalidada) se descarta.
 */
class MovementEditViewModel(
    private val saved: SavedStateHandle,
    private val clients: ClientRepository,
    private val movements: MovementRepository,
    private val evaluator: CorrectionEvaluator = HistoryCorrectionEvaluator(movements),
) : ViewModel() {
    private val movementId: String = checkNotNull(saved[MOVEMENT_ID_ARG])
    private val requestId: String = saved[REQUEST_ID] ?: UUID.randomUUID().toString().also { saved[REQUEST_ID] = it }

    private val _state = MutableStateFlow(MovementEditState())
    val state: StateFlow<MovementEditState> = _state.asStateFlow()

    /** Cada revisión nueva o cancelada invalida las evaluaciones anteriores; solo se aplica la vigente. */
    private var reviewToken = 0L

    /** Fecha máxima admitida: hoy en America/Bogota. */
    val today: LocalDate get() = movements.today()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val movement = try {
            movements.observeMovement(movementId).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val account = movement?.let { runCatching { clients.observeClient(it.clientId).first() }.getOrNull() }
        if (movement == null || account == null) {
            _state.update { it.copy(loading = false, failure = EditFailure.NOT_FOUND) }
            return
        }
        // La versión que la usuaria vio al abrir; si cambió después de recrear el proceso, se avisa.
        val expected = saved.get<Long>(EXPECTED_VERSION) ?: movement.version.also { saved[EXPECTED_VERSION] = it }
        val failure = when {
            !movement.active -> EditFailure.ALREADY_ANNULLED
            movement.version != expected -> EditFailure.VERSION_CONFLICT
            else -> null
        }
        _state.update {
            MovementEditState(
                loading = false,
                original = movement,
                clientName = account.client.name,
                amountText = saved[AMOUNT] ?: groupedAmount(movement.amountCop),
                date = LocalDate.parse(saved[DATE] ?: movement.date.toString()),
                description = saved[DESCRIPTION] ?: movement.description.orEmpty(),
                reason = saved[REASON] ?: "",
                failure = failure,
            ).withDirtyFlag()
        }
    }

    fun onAmountChange(value: String) = edit(AMOUNT, value) { copy(amountText = value, amountError = null) }

    fun onDateChange(value: LocalDate) = edit(DATE, value.toString()) { copy(date = value, dateError = null) }

    fun onDescriptionChange(value: String) = edit(DESCRIPTION, value) { copy(description = value, descriptionError = null) }

    fun onReasonChange(value: String) = edit(REASON, value) { copy(reason = value, reasonError = null) }

    /** Valida y muestra lo que cambiará. Sin cambios no hay nada que guardar ni revisión que crear. */
    fun review() {
        val current = _state.value
        val original = current.original ?: return
        val date = current.date ?: return
        // Ni una segunda evaluación mientras hay una pendiente, ni con una vista previa abierta.
        if (current.saving || current.loading || current.reviewing || current.failure.blocksEditing() ||
            current.preview != null || current.savedVersion != null
        ) {
            return
        }
        val validation = MovementRules.validate(MovementInput(current.amountText, date, current.description), today)
        if (validation is MovementValidation.Invalid) {
            _state.update { it.copy(amountError = validation.amount, dateError = validation.date, descriptionError = validation.description) }
            return
        }
        val valid = (validation as MovementValidation.Valid).movement
        val before = original.toSnapshot()
        val after = before.copy(
            amountCop = valid.amountCop,
            date = valid.date,
            description = valid.description,
            origin = MovementRules.originAfterCorrection(original.origin, original.amountCop, valid.amountCop),
        )
        val changes = before.changesTo(after)
        if (changes.isEmpty()) {
            _state.update { it.copy(noChanges = true) }
            return
        }
        MovementRules.validateReason(current.reason)?.let { error ->
            _state.update { it.copy(reasonError = error) }
            return
        }
        // La solicitud se congela aquí: es lo único que se mostrará y, si se confirma, lo único que se guardará.
        val request = CorrectionRequest(
            amountText = current.amountText,
            date = date,
            description = current.description,
            reason = current.reason,
            expectedVersion = checkNotNull(saved.get<Long>(EXPECTED_VERSION)),
        )
        // «Evaluando» se marca de forma atómica y síncrona: bloquea el formulario y cualquier otra revisión
        // antes de lanzar nada; si otro estado se coló entre la lectura y este punto, no se evalúa.
        if (!_state.compareAndSet(current, current.copy(reviewing = true, noChanges = false))) return
        val token = ++reviewToken
        viewModelScope.launch {
            val evaluation = try {
                evaluator.evaluate(original, after.origin, valid.amountCop, valid.date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CorrectionEvaluation(null, null)
            }
            _state.update { s ->
                if (token != reviewToken || !s.reviewing) {
                    s // evaluación invalidada mientras esperaba: el resultado tardío se descarta
                } else if (evaluation.rejection != null) {
                    // Se avisa antes de pedir confirmación: no se muestra un saldo imposible.
                    s.copy(reviewing = false, rejection = evaluation.rejection)
                } else {
                    s.copy(
                        reviewing = false,
                        preview = CorrectionPreview(request, changes, current.clientName, evaluation.newBalanceCop),
                    )
                }
            }
        }
    }

    /** Cierra la vista previa o cancela la evaluación pendiente; un resultado que llegue después se descarta. */
    fun dismissPreview() {
        _state.update {
            if (it.saving) {
                it
            } else {
                reviewToken++
                it.copy(preview = null, reviewing = false)
            }
        }
    }

    fun confirm() {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.saving || current.reviewing || current.savedVersion != null) return
        // Se marca «guardando» de forma síncrona: un doble toque no lanza dos guardados.
        _state.update { it.copy(saving = true, failure = null, rejection = null) }
        val request = preview.request
        viewModelScope.launch {
            val result = try {
                // Exactamente lo revisado, nunca los campos actuales.
                movements.correct(
                    requestId,
                    movementId,
                    request.expectedVersion,
                    CorrectionInput(request.amountText, request.date, request.description, request.reason),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { it.copy(saving = false, preview = null).withResult(result) }
        }
    }

    private fun MovementEditState.withResult(result: ChangeMovementResult?): MovementEditState = when (result) {
        null -> copy(failure = EditFailure.SAVE_FAILED)
        is ChangeMovementResult.Saved -> copy(savedVersion = result.newVersion, isDirty = false)
        ChangeMovementResult.Unchanged -> copy(noChanges = true)
        is ChangeMovementResult.Invalid -> withErrors(result.errors)
        is ChangeMovementResult.Rejected -> copy(rejection = result.reason)
        ChangeMovementResult.VersionConflict -> copy(failure = EditFailure.VERSION_CONFLICT)
        ChangeMovementResult.AlreadyAnnulled -> copy(failure = EditFailure.ALREADY_ANNULLED)
        ChangeMovementResult.Conflict -> copy(failure = EditFailure.CONFLICT)
        ChangeMovementResult.ClientArchived -> copy(failure = EditFailure.CLIENT_ARCHIVED)
        ChangeMovementResult.NotFound -> copy(failure = EditFailure.NOT_FOUND)
    }

    private fun MovementEditState.withErrors(errors: CorrectionInvalid) =
        copy(amountError = errors.amount, dateError = errors.date, descriptionError = errors.description, reasonError = errors.reason)

    private fun EditFailure?.blocksEditing() = this == EditFailure.NOT_FOUND || this == EditFailure.ALREADY_ANNULLED ||
        this == EditFailure.VERSION_CONFLICT

    // Mientras se evalúa o se muestra la vista previa, el formulario no cambia: lo mostrado es lo que se confirma.
    private inline fun edit(key: String, value: String, change: MovementEditState.() -> MovementEditState) {
        val current = _state.value
        if (current.saving || current.loading || current.reviewing || current.savedVersion != null || current.preview != null) return
        saved[key] = value
        _state.update { it.change().copy(noChanges = false, rejection = null, failure = if (it.failure.blocksEditing()) it.failure else null).withDirtyFlag() }
    }

    private fun MovementEditState.withDirtyFlag(): MovementEditState {
        val base = original ?: return this
        return copy(
            isDirty = amountText != groupedAmount(base.amountCop) || date != base.date ||
                description != base.description.orEmpty() || reason.isNotBlank(),
        )
    }

    private fun MovementEntity.toSnapshot() = MovementSnapshot(
        id, clientId, date, entryOrder, type, amountCop, description, origin, createdAtMillis, modifiedAtMillis, active, version,
    )

    companion object {
        const val MOVEMENT_ID_ARG = MovementDetailViewModel.MOVEMENT_ID_ARG
        private const val REQUEST_ID = "requestId"
        private const val EXPECTED_VERSION = "expectedVersion"
        private const val AMOUNT = "amount"
        private const val DATE = "date"
        private const val DESCRIPTION = "description"
        private const val REASON = "reason"

        /** Importe con agrupación colombiana («25.000»), que el lector de importes acepta tal cual. */
        fun groupedAmount(amountCop: Long): String = amountCop.toString().reversed().chunked(3).joinToString(".").reversed()

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = pagaYaContainer()
                MovementEditViewModel(createSavedStateHandle(), container.clientRepository, container.movementRepository)
            }
        }
    }
}
