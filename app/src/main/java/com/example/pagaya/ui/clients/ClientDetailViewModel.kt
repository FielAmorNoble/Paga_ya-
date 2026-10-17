package com.example.pagaya.ui.clients

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.ClientStatusResult
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.RegisterMovementResult
import com.example.pagaya.data.local.ClientWithAccount
import com.example.pagaya.domain.HistoryRow
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

sealed interface ClientDetailState {
    data object Loading : ClientDetailState

    /** [history] va del movimiento más reciente al más antiguo, con el saldo tras cada uno. */
    data class Loaded(val account: ClientWithAccount, val history: List<HistoryRow>) : ClientDetailState

    data object NotFound : ClientDetailState

    data object Error : ClientDetailState
}

enum class PayFullFailure { SAVE_FAILED, REVIEW_DATES, UNAVAILABLE }

/** Diálogos del pago de saldo completo. */
sealed interface PayFullState {
    data object Hidden : PayFullState

    /** Importe que la usuaria está confirmando; el repositorio lo vuelve a comprobar al guardar. */
    data class Confirm(val amountCop: Long, val saving: Boolean = false, val failure: PayFullFailure? = null) : PayFullState

    /** El saldo cambió entre la confirmación y el guardado: no se guardó nada. */
    data class BalanceChanged(val currentBalanceCop: Long) : PayFullState

    /** El saldo supera el máximo por movimiento: debe dividirse en abonos. */
    data class TooLarge(val balanceCop: Long) : PayFullState
}

/** Diálogos de archivar el cliente. */
sealed interface ArchiveState {
    data object Hidden : ArchiveState

    /** Confirmación: se conserva el historial y se puede reactivar. */
    data class Confirm(val saving: Boolean = false, val failed: Boolean = false) : ArchiveState

    /** Tiene deuda: primero debe quedar saldada. No se ofrece liquidarla. */
    data class HasDebt(val balanceCop: Long) : ArchiveState
}

class ClientDetailViewModel(
    savedState: SavedStateHandle,
    private val clients: ClientRepository,
    private val movements: MovementRepository,
) : ViewModel() {
    private val clientId: String = checkNotNull(savedState[ClientFormViewModel.CLIENT_ID_ARG])
    private val retries = MutableStateFlow(0)
    private val _state = MutableStateFlow<ClientDetailState>(ClientDetailState.Loading)
    val state: StateFlow<ClientDetailState> = _state.asStateFlow()

    private val _payFull = MutableStateFlow<PayFullState>(PayFullState.Hidden)
    val payFull: StateFlow<PayFullState> = _payFull.asStateFlow()

    private val _paymentSaved = MutableStateFlow(false)

    /** `true` desde que se guardó un pago completo hasta que la pantalla lo avisa con [consumePaymentSaved]. */
    val paymentSaved: StateFlow<Boolean> = _paymentSaved.asStateFlow()

    private val _archive = MutableStateFlow<ArchiveState>(ArchiveState.Hidden)
    val archive: StateFlow<ArchiveState> = _archive.asStateFlow()

    private val _archived = MutableStateFlow<String?>(null)

    /** Nombre del cliente recién archivado: la pantalla vuelve a la lista de origen y lo avisa allí. */
    val archived: StateFlow<String?> = _archived.asStateFlow()

    private val _reactivating = MutableStateFlow(false)
    val reactivating: StateFlow<Boolean> = _reactivating.asStateFlow()

    private val _reactivated = MutableStateFlow(false)

    /** `true` desde que se reactivó hasta que la pantalla lo avisa con [consumeReactivated]. */
    val reactivated: StateFlow<Boolean> = _reactivated.asStateFlow()

    private val _reactivateFailed = MutableStateFlow(false)
    val reactivateFailed: StateFlow<Boolean> = _reactivateFailed.asStateFlow()

    // Un intento de pago completo conserva su ID mientras el diálogo siga abierto; se reutiliza en reintentos.
    private var payFullRequestId: String = ""
    private var payFullDate: LocalDate = movements.today()

    init {
        viewModelScope.launch {
            retries.collectLatest {
                try {
                    combine(clients.observeClient(clientId), movements.observeHistory(clientId)) { account, history ->
                        account?.let { ClientDetailState.Loaded(it, history) } ?: ClientDetailState.NotFound
                    }.collect { _state.value = it }
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

    fun consumePaymentSaved() {
        _paymentSaved.value = false
    }

    fun startPayFull() {
        val loaded = _state.value as? ClientDetailState.Loaded ?: return
        val balance = loaded.account.balanceCop
        if (balance <= 0 || _payFull.value != PayFullState.Hidden) return
        if (balance > MovementRules.AMOUNT_MAX) {
            _payFull.value = PayFullState.TooLarge(balance)
            return
        }
        payFullRequestId = UUID.randomUUID().toString()
        payFullDate = movements.today()
        _payFull.value = PayFullState.Confirm(balance)
    }

    fun dismissPayFull() {
        if ((_payFull.value as? PayFullState.Confirm)?.saving == true) return
        _payFull.value = PayFullState.Hidden
    }

    fun confirmPayFull() {
        val current = _payFull.value as? PayFullState.Confirm ?: return
        // Se marca "guardando" de forma síncrona: un doble toque no lanza dos guardados.
        if (current.saving) return
        _payFull.value = current.copy(saving = true, failure = null)
        viewModelScope.launch {
            val result = try {
                movements.payFullBalance(payFullRequestId, clientId, current.amountCop, payFullDate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                is RegisterMovementResult.Saved -> {
                    _paymentSaved.value = true
                    _payFull.value = PayFullState.Hidden
                }
                is RegisterMovementResult.BalanceChanged -> _payFull.value = PayFullState.BalanceChanged(result.currentBalanceCop)
                is RegisterMovementResult.ExceedsLimit -> _payFull.value = PayFullState.TooLarge(result.balanceCop)
                else -> _payFull.update {
                    (it as? PayFullState.Confirm)?.copy(saving = false, failure = failureFor(result)) ?: it
                }
            }
        }
    }

    /** Abre la confirmación de archivar, o la explicación de que primero debe saldarse la deuda. */
    fun startArchive() {
        val loaded = _state.value as? ClientDetailState.Loaded ?: return
        if (!loaded.account.client.active || _archive.value != ArchiveState.Hidden) return
        val balance = loaded.account.balanceCop
        _archive.value = if (balance != 0L) ArchiveState.HasDebt(balance) else ArchiveState.Confirm()
    }

    fun dismissArchive() {
        if ((_archive.value as? ArchiveState.Confirm)?.saving == true) return
        _archive.value = ArchiveState.Hidden
    }

    fun confirmArchive() {
        val current = _archive.value as? ArchiveState.Confirm ?: return
        // Se marca «guardando» de forma síncrona: un doble toque no lanza dos escrituras.
        if (current.saving) return
        _archive.value = current.copy(saving = true, failed = false)
        val name = (_state.value as? ClientDetailState.Loaded)?.account?.client?.name.orEmpty()
        viewModelScope.launch {
            val result = try {
                clients.archive(clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _archive.value = when (result) {
                // Ya archivado (por ejemplo, un reintento): el resultado para la usuaria es el mismo.
                ClientStatusResult.Changed, ClientStatusResult.Unchanged -> {
                    _archived.value = name
                    ArchiveState.Hidden
                }
                // Se registró deuda entre la confirmación y el guardado: no se archivó nada.
                is ClientStatusResult.BalanceNotZero -> ArchiveState.HasDebt(result.balanceCop)
                ClientStatusResult.NotFound, null -> current.copy(saving = false, failed = true)
            }
        }
    }

    fun consumeArchived() {
        _archived.value = null
    }

    fun reactivate() {
        val loaded = _state.value as? ClientDetailState.Loaded ?: return
        if (loaded.account.client.active) return
        // Síncrono: un doble toque no lanza dos escrituras (el repositorio, además, no repite cambios).
        if (!_reactivating.compareAndSet(expect = false, update = true)) return
        _reactivateFailed.value = false
        viewModelScope.launch {
            val result = try {
                clients.reactivate(clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                ClientStatusResult.Changed, ClientStatusResult.Unchanged -> _reactivated.value = true
                else -> _reactivateFailed.value = true
            }
            _reactivating.value = false
        }
    }

    fun consumeReactivated() {
        _reactivated.value = false
    }

    private fun failureFor(result: RegisterMovementResult?) = when (result) {
        null -> PayFullFailure.SAVE_FAILED
        // La fecha del pago es hoy: solo falla si hay movimientos con fecha posterior (reloj atrasado).
        is RegisterMovementResult.Rejected, is RegisterMovementResult.Invalid -> PayFullFailure.REVIEW_DATES
        else -> PayFullFailure.UNAVAILABLE
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = pagaYaContainer()
                ClientDetailViewModel(createSavedStateHandle(), container.clientRepository, container.movementRepository)
            }
        }
    }
}
