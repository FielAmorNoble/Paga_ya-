package com.example.pagaya.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.backup.RestoreNotice
import com.example.pagaya.data.backup.RestoreService
import com.example.pagaya.pagaYaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class LedgerSessionViewModel(private val clients: ClientRepository, private val restore: RestoreService) : ViewModel() {
    private val _epoch = MutableStateFlow<String?>(null)
    val epoch = _epoch.asStateFlow()
    private val _notice = MutableStateFlow<RestoreNotice?>(null)
    val notice = _notice.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                clients.observeEpoch().distinctUntilChanged().collect {
                    _epoch.value = it
                    _notice.value = restore.recoverInterrupted()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _failed.value = true }
        }
    }
    fun acknowledge() {
        val current = _notice.value ?: return
        viewModelScope.launch { restore.acknowledge(current.id); _notice.value = null }
    }
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { pagaYaContainer().let { LedgerSessionViewModel(it.clientRepository, it.restoreService) } }
        }
    }
}
