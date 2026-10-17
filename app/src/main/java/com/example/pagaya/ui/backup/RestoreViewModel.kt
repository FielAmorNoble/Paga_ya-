package com.example.pagaya.ui.backup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.backup.*
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RestoreUiState(val choosing: Boolean = false, val validating: Boolean = false, val applying: Boolean = false,
    val preview: RestorePreview? = null, val changed: Boolean = false, val hasRecovery: Boolean = false,
    val failure: RestoreFailure? = null, val busyFailure: Boolean = false) {
    val busy get() = choosing || validating || applying
}

class RestoreViewModel(private val saved: SavedStateHandle, private val service: RestoreService) : ViewModel() {
    private val _state = MutableStateFlow(RestoreUiState())
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val oldPhase = saved.get<String>(PHASE)
                if (oldPhase == "applying") {
                    val outcome = service.recoverInterrupted()
                    if (outcome?.committed != true) _state.value = _state.value.copy(failure = RestoreFailure.INTERRUPTED)
                } else if (oldPhase == "validating" || oldPhase == "preview") {
                    _state.value = _state.value.copy(failure = RestoreFailure.EXPIRED)
                } else if (oldPhase == "choosing") _state.value = _state.value.copy(choosing = true)
                _state.value = _state.value.copy(hasRecovery = service.hasRecovery())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(failure = RestoreFailure.READ) }
        }
    }
    fun choose(): Boolean {
        if (_state.value.busy || _state.value.preview != null) return false
        saved[PHASE] = "choosing"
        _state.value = _state.value.copy(choosing = true, failure = null, busyFailure = false)
        return true
    }
    fun chosen(uri: String?) {
        if (uri == null) { saved[PHASE] = null; _state.value = _state.value.copy(choosing = false); return }
        prepare { service.prepare(uri) }
    }
    fun recoverPrevious() { if (!_state.value.busy && _state.value.preview == null) prepare { service.prepareRecovery() } }
    private fun prepare(block: suspend () -> RestorePreparation) {
        saved[PHASE] = "validating"
        _state.value = _state.value.copy(choosing = false, validating = true, failure = null, busyFailure = false)
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is RestorePreparation.Ready -> {
                        saved[PHASE] = "preview"
                        _state.value = _state.value.copy(validating = false, preview = result.preview, changed = false)
                    }
                    is RestorePreparation.Failed -> { saved[PHASE] = null; _state.value = _state.value.copy(validating = false, failure = result.failure) }
                    RestorePreparation.Busy -> { saved[PHASE] = null; _state.value = _state.value.copy(validating = false, busyFailure = true) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { saved[PHASE] = null; _state.value = _state.value.copy(validating = false, failure = RestoreFailure.READ) }
        }
    }
    fun confirm() {
        val preview = _state.value.preview ?: return
        if (_state.value.busy) return
        saved[PHASE] = "applying"
        _state.value = _state.value.copy(applying = true, failure = null, busyFailure = false)
        viewModelScope.launch {
            val result = try { service.confirm(preview.id) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { RestoreApplyResult.Failed(RestoreFailure.APPLY) }
            when (result) {
                is RestoreApplyResult.Applied -> { saved[PHASE] = null; _state.value = _state.value.copy(applying = false, preview = null) }
                is RestoreApplyResult.ReviewRequired -> { saved[PHASE] = "preview"; _state.value = _state.value.copy(applying = false, preview = result.preview, changed = true) }
                is RestoreApplyResult.Failed -> { saved[PHASE] = null; _state.value = _state.value.copy(applying = false, preview = null, failure = result.failure) }
                RestoreApplyResult.Busy -> { saved[PHASE] = "preview"; _state.value = _state.value.copy(applying = false, busyFailure = true) }
            }
        }
    }
    fun cancelPreview() {
        if (_state.value.applying) return
        val id = _state.value.preview?.id ?: return
        _state.value = _state.value.copy(preview = null, changed = false)
        saved[PHASE] = null
        viewModelScope.launch { service.cancel(id) }
    }
    companion object {
        private const val PHASE = "restorePhase"
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RestoreViewModel(createSavedStateHandle(), pagaYaContainer().restoreService) }
        }
    }
}
