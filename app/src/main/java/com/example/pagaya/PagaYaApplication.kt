package com.example.pagaya

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras

class PagaYaApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Acceso al contenedor desde las fábricas de ViewModel, sin framework de inyección. */
fun CreationExtras.pagaYaContainer(): AppContainer = (checkNotNull(this[APPLICATION_KEY]) as PagaYaApplication).container
