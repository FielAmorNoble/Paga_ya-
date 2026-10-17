package com.example.pagaya.ui.common

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pagaya.data.LedgerCoordination
import kotlinx.coroutines.async

/** La navegación entrega el token. El fallback sirve para propietarios de VM fuera del NavHost
 * (incluidas pruebas) y se guarda antes de cualquier intento, también al recrear el proceso.
 */
internal fun ViewModel.writeEpoch(saved: SavedStateHandle, load: suspend () -> String) = viewModelScope.async {
    saved.get<String>(LedgerCoordination.EPOCH_ARG) ?: load().also { saved[LedgerCoordination.EPOCH_ARG] = it }
}
