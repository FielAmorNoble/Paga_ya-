package com.example.pagaya

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.pagaya.data.ClientRepository
import com.example.pagaya.data.MovementRepository
import com.example.pagaya.data.backup.BackupService
import com.example.pagaya.data.backup.ContentResolverDocuments
import com.example.pagaya.data.backup.RestoreService
import com.example.pagaya.data.local.PagaYaDatabase
import java.io.File
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

    val movementRepository: MovementRepository by lazy { MovementRepository(database, clock) }

    /** Temporales del respaldo en almacenamiento privado excluido de cualquier respaldo del sistema. */
    val backupService: BackupService by lazy {
        BackupService(
            db = database,
            clock = clock,
            workDir = File(appContext.noBackupFilesDir, BackupService.WORK_DIR),
            appVersion = appVersion(),
            documents = ContentResolverDocuments(appContext.contentResolver),
        )
    }

    val restoreService: RestoreService by lazy {
        RestoreService(appContext, database, backupService, ContentResolverDocuments(appContext.contentResolver), clock,
            File(appContext.noBackupFilesDir, RestoreService.WORK_DIR), File(appContext.noBackupFilesDir, RestoreService.RECOVERY_DIR))
    }

    private fun appVersion(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return info.versionName?.takeIf { it.isNotBlank() } ?: "desconocida"
    }
}
