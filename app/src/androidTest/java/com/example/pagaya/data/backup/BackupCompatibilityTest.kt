package com.example.pagaya.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: archivos históricos SINTÉTICOS copiados a cache/test-legacy-N.pagaya.zip.
 * Nunca abre bases operativas ni convierte datos personales en fixtures versionados.
 */
@RunWith(AndroidJUnit4::class)
class BackupCompatibilityTest {
    @Test fun previousSyntheticQaExportsRemainValid() {
        val count = InstrumentationRegistry.getArguments().getString("syntheticCompatibilityCount")?.toInt() ?: 0
        assumeTrue("Requiere copias sintéticas locales explícitas", count > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(count) { index ->
            val file = File(context.cacheDir, "test-legacy-${index + 1}.pagaya.zip")
            try {
                assertTrue("Falta ${file.name}", file.isFile)
                val result = BackupValidator().validate(file)
                assertTrue("${file.name}: $result", result is BackupValidation.Valid)
                val manifest = (result as BackupValidation.Valid).summary.manifest
                assertEquals(3L, manifest.clients)
                assertEquals(if (index == 0) 5L else 6L, manifest.movements)
                assertEquals(3L, manifest.revisions)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "COMPAT ${file.name}: ${manifest.backupId} revision=${manifest.dataRevision} VALID\n")
                })
            } finally { file.delete() }
        }
    }
}
