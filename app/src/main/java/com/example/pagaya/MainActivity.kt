package com.example.pagaya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.example.pagaya.ui.PagaYaNavHost
import com.example.pagaya.ui.theme.PagaYaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Inicio nuevo (no una recreación): ninguna copia puede seguir en curso. Se cierran los intentos que
        // quedaron «Preparando» y se borran solo los temporales propios. Tras recrear la actividad no se hace
        // aquí: el selector de destino puede seguir abierto y lo resuelve la pantalla de respaldo.
        if (savedInstanceState == null) {
            val backup = (application as PagaYaApplication).container.backupService
            lifecycleScope.launch {
                try {
                    backup.recoverInterrupted(keepBackupId = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Si la base no se puede abrir, la lista de clientes ya lo informa.
                }
            }
        }
        setContent {
            PagaYaTheme {
                PagaYaNavHost()
            }
        }
    }
}
