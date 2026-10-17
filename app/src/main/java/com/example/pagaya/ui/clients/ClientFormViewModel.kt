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
import com.example.pagaya.data.SaveClientResult
import com.example.pagaya.domain.ClientInput
import com.example.pagaya.domain.ClientRules
import com.example.pagaya.domain.ClientValidation
import com.example.pagaya.domain.NameError
import com.example.pagaya.domain.NoteError
import com.example.pagaya.domain.PhoneError
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class FormFailure { SAVE_FAILED, CONFLICT, NOT_FOUND }

data class ClientFormState(
    val isEdit: Boolean,
    val loading: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val note: String = "",
    val nameError: NameError? = null,
    val phoneError: PhoneError? = null,
    val noteError: NoteError? = null,
    val homonymWarning: Boolean = false,
    val saving: Boolean = false,
    val failure: FormFailure? = null,
    val savedClientId: String? = null,
    val isDirty: Boolean = false,
)

/**
 * Crear (sin `clientId`) o editar un cliente. Lo escrito y el ID de solicitud se guardan en
 * [SavedStateHandle]: sobreviven a rotaciones y a la recreación del proceso, y un reintento
 * reutiliza el mismo ID, de modo que el repositorio no duplica el cliente.
 */
class ClientFormViewModel(
    private val saved: SavedStateHandle,
    private val repository: ClientRepository,
) : ViewModel() {
    private val epoch = writeEpoch(saved, repository::currentEpoch)

    private val clientId: String? = saved[CLIENT_ID_ARG]
    private val requestId: String = saved[REQUEST_ID] ?: UUID.randomUUID().toString().also { saved[REQUEST_ID] = it }

    private val _state = MutableStateFlow(
        ClientFormState(
            isEdit = clientId != null,
            loading = clientId != null && saved.get<Boolean>(LOADED) != true,
            name = saved[NAME] ?: "",
            phone = saved[PHONE] ?: "",
            note = saved[NOTE] ?: "",
        ).withDirtyFlag(),
    )
    val state: StateFlow<ClientFormState> = _state.asStateFlow()

    init {
        if (_state.value.loading) loadExisting()
        watchHomonyms()
    }

    fun onNameChange(value: String) = edit(NAME, value) { copy(name = value, nameError = null) }

    fun onPhoneChange(value: String) = edit(PHONE, value) { copy(phone = value, phoneError = null) }

    fun onNoteChange(value: String) = edit(NOTE, value) { copy(note = value, noteError = null) }

    fun save() {
        val current = _state.value
        // Se marca "guardando" de forma síncrona: un doble toque no lanza dos guardados.
        if (current.saving || current.loading || current.savedClientId != null) return
        val input = ClientInput(current.name, current.phone, current.note)
        val validation = ClientRules.validate(input)
        if (validation is ClientValidation.Invalid) {
            _state.update { it.withErrors(validation) }
            return
        }
        _state.update { it.copy(saving = true, failure = null) }
        viewModelScope.launch {
            val result = try {
                if (clientId == null) repository.create(requestId, input, epoch.await()) else repository.update(clientId, input, epoch.await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update {
                when (result) {
                    null -> it.copy(saving = false, failure = FormFailure.SAVE_FAILED)
                    is SaveClientResult.Saved -> it.copy(saving = false, savedClientId = result.clientId, isDirty = false)
                    is SaveClientResult.Invalid -> it.copy(saving = false).withErrors(result.errors)
                    SaveClientResult.Conflict -> it.copy(saving = false, failure = FormFailure.CONFLICT)
                    SaveClientResult.NotFound -> it.copy(saving = false, failure = FormFailure.NOT_FOUND)
                }
            }
        }
    }

    private fun loadExisting() {
        viewModelScope.launch {
            val existing = try {
                repository.observeClient(checkNotNull(clientId)).first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (existing == null) {
                _state.update { it.copy(loading = false, failure = FormFailure.NOT_FOUND) }
                return@launch
            }
            val client = existing.client
            saved[INITIAL_NAME] = client.name
            saved[INITIAL_PHONE] = client.phone.orEmpty()
            saved[INITIAL_NOTE] = client.note.orEmpty()
            saved[NAME] = client.name
            saved[PHONE] = client.phone.orEmpty()
            saved[NOTE] = client.note.orEmpty()
            saved[LOADED] = true
            _state.update {
                it.copy(loading = false, name = client.name, phone = client.phone.orEmpty(), note = client.note.orEmpty())
                    .withDirtyFlag()
            }
        }
    }

    // Aviso no bloqueante: se consulta tras una breve pausa al escribir.
    private fun watchHomonyms() {
        viewModelScope.launch {
            _state.map { it.name }.distinctUntilChanged().collectLatest { name ->
                delay(HOMONYM_DELAY_MS)
                val count = try {
                    // El propio cliente (o el que este formulario creó) no cuenta como homónimo.
                    repository.countHomonyms(name, clientId ?: requestId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    0
                }
                _state.update { it.copy(homonymWarning = count > 0) }
            }
        }
    }

    private inline fun edit(key: String, value: String, change: ClientFormState.() -> ClientFormState) {
        if (_state.value.saving || _state.value.loading) return
        saved[key] = value
        _state.update { it.change().copy(failure = null).withDirtyFlag() }
    }

    private fun ClientFormState.withDirtyFlag() = copy(
        isDirty = name != (saved[INITIAL_NAME] ?: "") ||
            phone != (saved[INITIAL_PHONE] ?: "") ||
            note != (saved[INITIAL_NOTE] ?: ""),
    )

    private fun ClientFormState.withErrors(errors: ClientValidation.Invalid) =
        copy(nameError = errors.name, phoneError = errors.phone, noteError = errors.note)

    companion object {
        const val CLIENT_ID_ARG = "clientId"
        private const val REQUEST_ID = "requestId"
        private const val NAME = "name"
        private const val PHONE = "phone"
        private const val NOTE = "note"
        private const val INITIAL_NAME = "initialName"
        private const val INITIAL_PHONE = "initialPhone"
        private const val INITIAL_NOTE = "initialNote"
        private const val LOADED = "loaded"
        private const val HOMONYM_DELAY_MS = 300L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ClientFormViewModel(createSavedStateHandle(), pagaYaContainer().clientRepository) }
        }
    }
}
