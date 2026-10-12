package com.example.pagaya

import android.content.Context
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.local.PagaYaDatabase
import java.time.Clock
import java.util.UUID

/** Contenedor manual de dependencias de la app. Una sola instancia, creada por [PagaYaApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val clock: Clock = Clock.systemUTC()

    /** Única instancia Room. Crearla no abre el archivo; la primera consulta sí, fuera del hilo principal. */
    val database: PagaYaDatabase by lazy {
        PagaYaDatabase.build(appContext, PagaYaDatabase.FILE_NAME, clock) { UUID.randomUUID().toString() }
    }

    val clientRepository: ClientRepository by lazy { ClientRepository(database, clock) }
}
